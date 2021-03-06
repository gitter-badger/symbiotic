package controllers

import com.google.inject.{Inject, Singleton}
import com.mohiva.play.silhouette.api.Authenticator.Implicits._
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.impl.providers.oauth2.GitHubProvider
import core.security.authentication.{GitHubEmail, JWTEnvironment}
import models.base.Username
import net.scalytica.symbiotic.json.Implicits.dateTimeFormatter
import org.joda.time.DateTime
import play.api.libs.ws.WSClient
import services.party.UserService
import com.mohiva.play.silhouette.api._
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.api.util.{Clock, Credentials}
import com.mohiva.play.silhouette.impl.exceptions.IdentityNotFoundException
import com.mohiva.play.silhouette.impl.providers._
import models.party.User
import net.ceedubs.ficus.Ficus._
import play.api.libs.json._
import play.api.mvc._
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

@Singleton
class LoginController @Inject()(
    val controllerComponents: ControllerComponents,
    silhouette: Silhouette[JWTEnvironment],
    userService: UserService,
    authInfoRepository: AuthInfoRepository,
    credentialsProvider: CredentialsProvider,
    socialProviderRegistry: SocialProviderRegistry,
    configuration: Configuration,
    clock: Clock,
    wsClient: WSClient
) extends SymbioticController {

  private[this] val log = Logger(this.getClass)

  private[this] val RememberMeExpiryKey =
    "silhouette.authenticator.rememberMe.authenticatorExpiry"
  private[this] val RememberMeIdleKey =
    "silhouette.authenticator.rememberMe.authenticatorIdleTimeout"

  /**
   * Provides service for logging in using regular username / password.
   */
  def login(): Action[JsValue] = Action.async(parse.json) { implicit request =>
    val creds = validateRequest
    credentialsProvider
      .authenticate(creds._1)
      .flatMap { loginInfo =>
        userService.retrieve(loginInfo).flatMap {
          case Some(user) =>
            val c = configuration.underlying
            silhouette.env.authenticatorService
              .create(loginInfo)
              .map {
                case authenticator if creds._2 =>
                  authenticator.copy(
                    expirationDateTime = clock.now + c.as[FiniteDuration](
                      RememberMeExpiryKey
                    ),
                    idleTimeout = c.getAs[FiniteDuration](RememberMeIdleKey)
                  )
                case authenticator =>
                  authenticator

              }
              .flatMap { authenticator =>
                silhouette.env.eventBus.publish(LoginEvent(user, request))
                silhouette.env.authenticatorService.init(authenticator).map {
                  v =>
                    Ok(Json.obj("token" -> v))
                }
              }
          case None =>
            Future.failed(new IdentityNotFoundException("Couldn't find user"))
        }
      }
      .recover {
        case _: ProviderException =>
          Unauthorized(Json.obj("msg" -> "Invalid credentials"))
      }
  }

  /**
   * Service for authenticating against 3rd party providers.
   *
   * @param provider The authentication provider
   */
  def authenticate(provider: String): Action[AnyContent] =
    Action.async { implicit request =>
      val socialProvider = socialProviderRegistry.get[SocialProvider](provider)
      val res = socialProvider match {
        case Some(p: SocialProvider with CommonSocialProfileBuilder) =>
          p.authenticate().flatMap {
            case Left(result) => Future.successful(result)
            case Right(authInfo) =>
              for {
                profile <- p.retrieveProfile(authInfo)
                _       <- Future.successful(log.info(s"Profile: $profile"))
                maybeEmail <- profile.email
                               .map(e => Future.successful(Option(e)))
                               .getOrElse(fetchEmail(p.id, p, authInfo))
                user <- fromSocialProfile(profile.copy(email = maybeEmail))
                _    <- Future.successful(userService.save(user))
                _    <- authInfoRepository.save(profile.loginInfo, authInfo)
                authenticator <- silhouette.env.authenticatorService
                                  .create(profile.loginInfo)
                value <- silhouette.env.authenticatorService.init(authenticator)
              } yield {
                silhouette.env.eventBus.publish(LoginEvent(user, request))
                Ok(
                  Json.obj(
                    "token" -> value,
                    "expiresOn" -> Json
                      .toJson[DateTime](authenticator.expirationDateTime)
                  )
                )
              }
          }
        case _ =>
          Future.failed(
            new ProviderException(s"Social provider $provider is not supported")
          )
      }
      res.recover {
        case e: ProviderException =>
          log.error("Unexpected provider error", e)
          Unauthorized(Json.obj("msg" -> e.getMessage))
      }
    }

  def logout: Action[AnyContent] =
    silhouette.UserAwareAction.async { implicit request =>
      val maybeFutRes = for {
        user          <- request.identity
        authenticator <- request.authenticator
      } yield {
        silhouette.env.eventBus.publish(LogoutEvent(user, request))
        silhouette.env.authenticatorService.discard(authenticator, Ok)
      }
      maybeFutRes.getOrElse(Future.successful(Ok))
    }

  private[this] def validateRequest(
      implicit request: Request[JsValue]
  ): (Credentials, Boolean) = {
    val theJson    = request.body
    val username   = (theJson \ "username").asOpt[String].getOrElse("")
    val password   = (theJson \ "password").asOpt[String].getOrElse("")
    val rememberMe = (theJson \ "rememberMe").asOpt[Boolean].getOrElse(false)

    (Credentials(username, password), rememberMe)
  }

  private[this] def fetchEmail(
      socialUid: String,
      provider: SocialProvider,
      a: AuthInfo
  ): Future[Option[String]] = {
    log.debug(
      s"Could not find any email for $socialUid in result. Going to " +
        s"looking up using the provider REST API"
    )

    val maybeUrl =
      configuration.getOptional[String](s"silhouette.${provider.id}.emailsURL")
    maybeUrl
      .map(
        u =>
          provider match {
            case _: GitHubProvider =>
              log.debug(s"Trying to fetch a emails for $socialUid from GitHub.")
              wsClient
                .url(u.format(a.asInstanceOf[OAuth2Info].accessToken))
                .get()
                .map { response =>
                  val emails =
                    response.json.asOpt[Seq[GitHubEmail]].getOrElse(Seq.empty)
                  emails.find(_.primary).map(_.email)
                }
                .recover {
                  case NonFatal(err) =>
                    log.warn(
                      s"There was an error fetching emails for $socialUid " +
                        "from GitHub."
                    )
                    log.debug(
                      s"Error fetching emails for $socialUid from GitHub",
                      err
                    )
                    None
                }
            case _ =>
              Future.successful(None)
        }
      )
      .getOrElse(Future.successful(None))
  }

  private[this] def fromSocialProfile(prof: CommonSocialProfile): Future[User] =
    userService.findByUsername(Username(prof.loginInfo.providerKey)).map {
      maybeUser =>
        User.updateFromCommonSocialProfile(prof, maybeUser)
    }

}
