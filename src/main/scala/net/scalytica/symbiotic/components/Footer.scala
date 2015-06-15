package net.scalytica.symbiotic.components

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import net.scalytica.symbiotic.css.Colors

import scalacss.Defaults._
import scalacss.ScalaCssReact._

object Footer {

  object Style extends StyleSheet.Inline {

    import dsl._

    val footer = style(addClassNames("page-footer", "transparent"))

    val copyright = style(
      addClassNames("footer-copyright"),
      Colors.DeepPurple.plain
    )

    val container = style(addClassName("container"))

  }

  val component = ReactComponentB.static("Footer",
    <.footer(Style.footer,
      <.div(Style.copyright,
        <.div(Style.container,
          "© Scalytica.net, all rights reserved."
        )
      )
    )
  ).buildU

  def apply() = component()
}
