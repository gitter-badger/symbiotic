package net.scalytica.symbiotic.core.converters

import scala.scalajs.js.Date

object DateConverters {

  def toReadableDate(ds: String): String = new Date(ds).toDateString()

}
