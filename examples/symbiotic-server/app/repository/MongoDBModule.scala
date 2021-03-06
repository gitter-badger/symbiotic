package repository

import akka.stream.Materializer
import com.google.inject.{AbstractModule, Provides}
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import play.api.Configuration
import repository.mongodb.party._
import repository.mongodb.silhouette.{
  MongoDBOAuth2Repository,
  MongoDBPasswordAuthRepository
}

class MongoDBModule extends AbstractModule {

  override def configure(): Unit = {}

  @Provides
  def userRepositoryProvider(configuration: Configuration): UserRepository = {
    new MongoDBUserRepository(configuration)
  }

  @Provides
  def avatarRepositoryProvider(
      configuration: Configuration,
      materializer: Materializer
  ): AvatarRepository = {
    new MongoDBAvatarRepository()(configuration, materializer)
  }

  @Provides
  def oauth2RepositoryProvider(
      configuration: Configuration
  ): DelegableAuthInfoDAO[OAuth2Info] = {
    new MongoDBOAuth2Repository(configuration)
  }

  @Provides
  def passAuthRepositoryProvider(
      configuration: Configuration
  ): DelegableAuthInfoDAO[PasswordInfo] = {
    new MongoDBPasswordAuthRepository(configuration)
  }

}
