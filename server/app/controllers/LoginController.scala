/**
 * Copyright(c) 2016 Knut Petter Meen, all rights reserved.
 */
package controllers

import com.google.inject.{Inject, Singleton}
import com.mohiva.play.silhouette.api.Authenticator.Implicits._
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.api.services.AvatarService
import com.mohiva.play.silhouette.impl.providers.oauth2.GitHubProvider
import core.converters.DateTimeConverters.dateTimeFormatter
import core.security.authentication.GitHubEmail
import models.base.Username
import org.joda.time.DateTime
import play.api.libs.ws.WSClient
import services.party.UserService
import com.mohiva.play.silhouette.api._
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.api.util.{Clock, Credentials}
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
import com.mohiva.play.silhouette.impl.exceptions.IdentityNotFoundException
import com.mohiva.play.silhouette.impl.providers._
import models.party.User
import net.ceedubs.ficus.Ficus._
import play.api.i18n.MessagesApi
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc._
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.concurrent.duration._

@Singleton
class LoginController @Inject() (
    val messagesApi: MessagesApi,
    val env: Environment[User, JWTAuthenticator],
    val userService: UserService,
    avatarService: AvatarService,
    authInfoRepository: AuthInfoRepository,
    credentialsProvider: CredentialsProvider,
    socialProviderRegistry: SocialProviderRegistry,
    configuration: Configuration,
    clock: Clock,
    wsClient: WSClient
) extends SymbioticController {

  private val log = Logger(this.getClass)

  private val RememberMeExpiryKey = "silhouette.authenticator.rememberMe.authenticatorExpiry"
  private val RememberMeIdleKey = "silhouette.authenticator.rememberMe.authenticatorIdleTimeout"

  /**
   * Provides service for logging in using regular username / password.
   */
  def login() = Action.async(parse.json) { implicit request =>
    val creds = validate
    credentialsProvider.authenticate(creds._1).flatMap { loginInfo =>
      userService.retrieve(loginInfo).flatMap {
        case Some(user) =>
          val c = configuration.underlying
          env.authenticatorService.create(loginInfo).map {
            case authenticator if creds._2 =>
              authenticator.copy(
                expirationDateTime = clock.now + c.as[FiniteDuration](RememberMeExpiryKey),
                idleTimeout = c.getAs[FiniteDuration](RememberMeIdleKey)
              )
            case authenticator =>
              authenticator

          }.flatMap { authenticator =>
            env.eventBus.publish(LoginEvent(user, request, request2Messages))
            env.authenticatorService.init(authenticator).map { v =>
              Ok(Json.obj("token" -> v))
            }
          }
        case None =>
          Future.failed(new IdentityNotFoundException("Couldn't find user"))
      }
    }.recover {
      case pe: ProviderException => Unauthorized(Json.obj("msg" -> "Invalid credentials"))
    }
  }

  private def fetchEmail(socialUid: String, provider: SocialProvider, a: AuthInfo): Future[Option[String]] = {
    log.debug(s"Could not find any email for $socialUid in result. Going to looking up using the provider REST API")
    val maybeUrl = configuration.getString(s"silhouette.${provider.id}.emailsURL")
    maybeUrl.map(u =>
      provider match {
        case gh: GitHubProvider =>
          log.debug(s"Trying to fetch a emails for $socialUid from GitHub.")
          wsClient.url(u.format(a.asInstanceOf[OAuth2Info].accessToken)).get()
            .map { response =>
              val emails: Seq[GitHubEmail] = response.json.asOpt[Seq[GitHubEmail]].getOrElse(Seq.empty[GitHubEmail])
              emails.find(_.primary).map(_.email)
            }
            .recover {
              case err: Exception =>
                log.warn(s"There was an error fetching emails for $socialUid from GitHub.")
                None
            }
        case _ =>
          Future.successful(None)
      }).getOrElse(Future.successful(None))
  }

  /**
   * Service for authenticating against 3rd party providers.
   *
   * @param provider The authentication provider
   */
  def authenticate(provider: String) = Action.async { implicit request =>
    (socialProviderRegistry.get[SocialProvider](provider) match {
      case Some(p: SocialProvider with CommonSocialProfileBuilder) =>
        p.authenticate().flatMap {
          case Left(result) => Future.successful(result)
          case Right(authInfo) =>
            for {
              profile <- p.retrieveProfile(authInfo)
              _ <- Future.successful(log.info(s"Profile Info: $profile"))
              maybeEmail <- if (profile.email.nonEmpty) Future.successful(profile.email) else fetchEmail(p.id, p, authInfo)
              user <- fromSocialProfile(profile.copy(email = maybeEmail))
              successOrFailure <- Future.successful(userService.save(user))
              authInfo <- authInfoRepository.save(profile.loginInfo, authInfo)
              authenticator <- env.authenticatorService.create(profile.loginInfo)
              value <- env.authenticatorService.init(authenticator)
            } yield {
              env.eventBus.publish(LoginEvent(user, request, request2Messages))
              Ok(Json.obj(
                "token" -> value,
                "expiresOn" -> Json.toJson[DateTime](authenticator.expirationDateTime)
              ))
            }
        }
      case _ =>
        Future.failed(new ProviderException(s"Social provider $provider is not supported"))
    }).recover {
      case e: ProviderException =>
        logger.error("Unexpected provider error", e)
        Unauthorized(Json.obj("msg" -> e.getMessage))
    }
  }

  private def fromSocialProfile(prof: CommonSocialProfile): Future[User] =
    Future.successful {
      val maybeUser = userService.findByUsername(Username(prof.loginInfo.providerKey))
      User.updateFromCommonSocialProfile(prof, maybeUser)
    }

  def logout = UserAwareAction.async { implicit request =>
    (for {
      user <- request.identity
      authenticator <- request.authenticator
    } yield {
      env.eventBus.publish(LogoutEvent(user, request, request2Messages))
      env.authenticatorService.discard(authenticator, Ok)
    }).getOrElse(Future.successful(Ok))
  }

  private def validate(implicit request: Request[JsValue]): (Credentials, Boolean) = {
    val theJson = request.body
    val username = (theJson \ "username").asOpt[String].getOrElse("")
    val password = (theJson \ "password").asOpt[String].getOrElse("")
    val rememberMe = (theJson \ "rememberMe").asOpt[Boolean].getOrElse(false)

    (Credentials(username, password), rememberMe)
  }

  private def unauthorized(msg: String) = {
    Unauthorized(
      Json.obj(
        "code" -> UNAUTHORIZED,
        "reason" -> "Access denied",
        "message" -> msg
      )
    )
  }
}