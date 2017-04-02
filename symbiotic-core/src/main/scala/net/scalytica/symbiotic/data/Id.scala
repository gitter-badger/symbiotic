/**
 * Copyright(c) 2017 Knut Petter Meen, all rights reserved.
 */
package net.scalytica.symbiotic.data

import java.util.UUID

import scala.util.{Failure, Success, Try}

/**
 * Base trait defining an Id throughout the system. All type specific Id's should extend this trait
 */
abstract class Id {

  val value: String

  assertId()

  def assertId(): AnyVal = {
    if (value.nonEmpty) {
      assert(
        assertion = Try(UUID.fromString(value)) match {
          case Success(s) => true
          case Failure(e) => false
        },
        message = "Value is not a valid format"
      )
    } else {
      // TODO: This should be handled better. It's basically a hack to allow creating empty Id's
      true
    }
  }
}