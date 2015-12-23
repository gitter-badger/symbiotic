/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package services.party

import core.lib.{Updated, Created, Success}
import core.security.authentication.Crypto
import models.base.Gender.Male
import models.base.{Email, Name, Username}
import models.party.PartyBaseTypes.UserId
import models.party.User
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import util.mongodb.MongoSpec

class UserServiceSpec extends Specification with MongoSpec {

  def buildUser(uname: Username, email: Email, name: Name): User =
    User(
      id = UserId.createOpt(),
      v = None,
      username = uname,
      email = email,
      password = Crypto.encryptPassword("asdfasdf"),
      name = Some(name),
      dateOfBirth = Some(DateTime.now().minusYears(20)), // scalastyle:ignore
      gender = Some(Male())
    )

  def saveAndValidate[A <: Success](usr: User, s: A) = UserService.save(usr) must_== s // scalastyle:ignore

  "When using the UserService it" should {
    "be possible to add a new User" in {
      val usr = buildUser(
        Username("foobar"),
        Email("foobar@fizzbuzz.no"),
        Name(Some("foo"), None, Some("bar"))
      )
      saveAndValidate(usr, Created)
    }

    "be possible to find a User by UserId" in {
      val usr = buildUser(
        Username("fiifaa"),
        Email("fiifaa@fizzbuzz.no"),
        Name(Some("fii"), None, Some("faa"))
      )
      saveAndValidate(usr, Created)

      val res = UserService.findById(usr.id.get) // scalastyle:ignore
      res must_!= None
      res.get.name must_== usr.name
      res.get.email must_== usr.email
      res.get.username must_== usr.username
    }

    "be possible to find a User by Username" in {
      val usr = buildUser(
        Username("boobaa"),
        Email("boobaa@fizzbuzz.no"),
        Name(Some("boo"), None, Some("baa"))
      )
      saveAndValidate(usr, Created)

      val res = UserService.findByUsername(usr.username) // scalastyle:ignore
      res must_!= None
      res.get.name must_== usr.name
      res.get.email must_== usr.email
      res.get.username must_== usr.username
    }

    "be possible to update a User" in {
      val usr = buildUser(
        Username("liiloo"),
        Email("liiloo@fizzbuzz.no"),
        Name(Some("lii"), None, Some("loo"))
      )
      saveAndValidate(usr, Created)

      val res1 = UserService.findById(usr.id.get) // scalastyle:ignore
      res1 must_!= None

      val mod = res1.get.copy(name = res1.get.name.map(_.copy(middle = Some("laa"))))
      saveAndValidate(mod, Updated)

      val res2 = UserService.findById(usr.id.get) // scalastyle:ignore
      res2 must_!= None
      res2.get.name must_== mod.name
      res2.get.email must_== usr.email
      res2.get.username must_== usr.username
    }
  }

}