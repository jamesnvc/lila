package controllers

import play.api.mvc._, Results._

import lila.api.Context
import lila.app._
import lila.app.mashup.GameFilterMenu
import lila.common.LilaCookie
import lila.db.api.$find
import lila.game.GameRepo
import lila.security.Permission
import lila.user.tube.userTube
import lila.user.{ User => UserModel, UserRepo }
import views._

object User extends LilaController {

  private def env = Env.user
  private def gamePaginator = Env.game.paginator
  private def forms = lila.user.DataForm
  private def relationApi = Env.relation.api

  def show(username: String) = Open { implicit ctx =>
    filter(username, none, 1)
  }

  def showMini(username: String) = Open { implicit ctx =>
    OptionFuResult(UserRepo named username) { user =>
      GameRepo nowPlaying user.id zip
        (ctx.userId ?? { relationApi.blocks(user.id, _) }) zip
        (ctx.userId ?? { relationApi.follows(user.id, _) }) zip
        (ctx.isAuth ?? { Env.pref.api.followable(user.id) }) zip
        (ctx.userId ?? { relationApi.relation(_, user.id) }) map {
          case ((((game, blocked), followed), followable), relation) =>
            Ok(html.user.mini(user, game, blocked, followed, followable, relation))
              .withHeaders(CACHE_CONTROL -> "max-age=5")
        }
    }
  }

  def showFilter(username: String, filterName: String, page: Int) = Open { implicit ctx =>
    filter(username, filterName.some, page)
  }

  def online = Open { implicit req =>
    val max = 1000
    UserRepo.byIdsSortRating(env.onlineUserIdMemo.keys, max) map { html.user.online(_, max) }
  }

  private def filter(
    username: String,
    filterOption: Option[String],
    page: Int,
    status: Results.Status = Results.Ok)(implicit ctx: Context) =
    Reasonable(page) {
      OptionFuResult(UserRepo named username) { u =>
        (u.enabled || isGranted(_.UserSpy)).fold({
          if (lila.common.HTTPRequest.isSynchronousHttp(ctx.req))
            userShow(u, filterOption, page)
          else
            userGames(u, filterOption, page)
        } map { status(_) },
          UserRepo isArtificial u.id map { artificial =>
            NotFound(html.user.disabled(u, artificial))
          })
      }
    }

  private def userShow(u: UserModel, filterOption: Option[String], page: Int)(implicit ctx: Context) = for {
    info ← Env.current.userInfo(u, ctx)
    filterName = filterOption | "all"
    filters = GameFilterMenu(info, ctx.me, filterName)
    pag ← (filters.query.fold(Env.bookmark.api.gamePaginatorByUser(u, page)) { query =>
      gamePaginator.recentlyCreated(query, filters.cachedNb)(page)
    })
    relation <- ctx.userId ?? { relationApi.relation(_, u.id) }
    notes <- ctx.me ?? { me =>
      relationApi friends me.id flatMap { env.noteApi.get(u, me, _) }
    }
    followable <- ctx.isAuth ?? { Env.pref.api followable u.id }
    blocked <- ctx.userId ?? { relationApi.blocks(u.id, _) }
  } yield html.user.show(u, info, pag, filters, relation, notes, followable, blocked)

  private def userGames(u: UserModel, filterOption: Option[String], page: Int)(implicit ctx: Context) = {
    val filterName = filterOption | "all"
    val current = GameFilterMenu.currentOf(GameFilterMenu.all, filterName)
    val query = GameFilterMenu.queryOf(current, u, ctx.me)
    val cachedNb = GameFilterMenu.cachedNbOf(u, current)
    query.fold(Env.bookmark.api.gamePaginatorByUser(u, page)) { query =>
      gamePaginator.recentlyCreated(query, cachedNb)(page)
    } map { html.user.games(u, _, filterName) }
  }

  def list = Open { implicit ctx =>
    val nb = 10
    for {
      bullet ← env.cached topBulletWeek nb
      blitz ← env.cached topBlitzWeek nb
      classical ← env.cached topClassicalWeek nb
      chess960 ← env.cached topClassicalWeek nb
      kingOfTheHill ← env.cached topKingOfTheHillWeek nb
      threeCheck ← env.cached topThreeCheckWeek nb
      nbAllTime ← env.cached topNbGame nb map2 { (user: UserModel) =>
        user -> user.count.game
      }
      nbWeek ← Env.game.cached activePlayerUidsWeek nb flatMap { pairs =>
        UserRepo.byOrderedIds(pairs.map(_._1)) map (_ zip pairs.map(_._2))
      }
      tourneyWinners ← Env.tournament.winners scheduled nb
      online ← env.cached topOnline 30
    } yield html.user.list(
      tourneyWinners = tourneyWinners,
      online = online,
      bullet = bullet,
      blitz = blitz,
      classical = classical,
      chess960 = chess960,
      kingOfTheHill = kingOfTheHill,
      threeCheck = threeCheck,
      nbWeek = nbWeek,
      nbAllTime = nbAllTime)
  }

  def leaderboard = Open { implicit ctx =>
    UserRepo topRating 500 map { users =>
      html.user.leaderboard(users)
    }
  }

  def mod(username: String) = Secure(_.UserSpy) { implicit ctx =>
    me => OptionFuOk(UserRepo named username) { user =>
      Env.evaluation.evaluator find user zip
        (Env.security userSpy user.id) map {
          case (eval, spy) => html.user.mod(user, spy, eval)
        }
    }
  }

  def evaluate(username: String) = Secure(_.UserEvaluate) { implicit ctx =>
    me => OptionFuResult(UserRepo named username) { user =>
      Env.evaluation.evaluator.generate(user, true) inject Redirect(routes.User.show(username).url + "?mod")
    }
  }

  def writeNote(username: String) = AuthBody { implicit ctx =>
    me => OptionFuResult(UserRepo named username) { user =>
      implicit val req = ctx.body
      env.forms.note.bindFromRequest.fold(
        err => filter(username, none, 1, Results.BadRequest),
        text => env.noteApi.write(user, text, me) inject Redirect(routes.User.show(username).url + "?note")
      )
    }
  }

  def opponents(username: String) = Open { implicit ctx =>
    OptionFuOk(UserRepo named username) { user =>
      lila.game.BestOpponents(user.id, 50) flatMap { ops =>
        ctx.isAuth.fold(
          Env.pref.api.followables(ops map (_._1.id)),
          fuccess(List.fill(50)(true))
        ) flatMap { followables =>
            (ops zip followables).map {
              case ((u, nb), followable) => ctx.userId ?? { myId =>
                relationApi.relation(myId, u.id)
              } map { lila.relation.Related(u, nb, followable, _) }
            }.sequenceFu map { relateds =>
              html.user.opponents(user, relateds)
            }
          }
      }
    }
  }

  def autocomplete = Open { implicit ctx =>
    get("term", ctx.req).filter(_.nonEmpty).fold(BadRequest("No search term provided").fuccess: Fu[Result]) { term =>
      JsonOk(UserRepo usernamesLike term)
    }
  }
}
