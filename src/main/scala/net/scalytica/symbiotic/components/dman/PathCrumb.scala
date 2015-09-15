/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package net.scalytica.symbiotic.components.dman

import java.util.UUID

import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.ExternalVar
import japgolly.scalajs.react.extra.router2.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import net.scalytica.symbiotic.css.{FontAwesome, Material, MaterialColors}
import net.scalytica.symbiotic.models.dman.File
import net.scalytica.symbiotic.routes.DMan.FolderPath

import scalacss.Defaults._
import scalacss.ScalaCssReact._

object PathCrumb {

  object Style extends StyleSheet.Inline {

    import dsl._

    val crumb = Material.cardDefault.compose(style(className = "crumb")(
      unsafeChild("a")(style(
        display.block,
        float.left,
        position.relative,
        fontSize(12.px),
        fontFamily := "Roboto Mono, monospace",
        height(20.px),
        backgroundColor(MaterialColors.IndigoLighten2),
        maxWidth(120.px),
        marginRight(2.px),
        textDecoration := "none",
        color.white,
        padding(2.px, 5.px, 2.px, 10.px),
        cursor.pointer,
        &.firstChild.before(
          content := "\"\"",
          borderBottom.`0`,
          borderLeft(2.px, solid, MaterialColors.IndigoLighten2)
        ),
        &.before(
          content := "\"\"",
          borderBottom(24.px, solid, transparent),
          borderLeft(10.px, solid, white),
          position.absolute,
          left.`0`,
          top.`0`
        ),
        &.after(
          content := "\"\"",
          borderBottom(24.px, solid, transparent),
          borderLeft(10.px, solid, MaterialColors.IndigoLighten2),
          position.absolute,
          right :=! "-10px",
          top.`0`,
          zIndex(10)
        ),
        unsafeChild("div")(style(
          maxWidth(70.px),
          overflow.hidden,
          textOverflow := "ellipsis"
        ))
      ))
    ))
  }

  case class Props(oid: String, path: String, selected: ExternalVar[Option[File]], routerCtl: RouterCtl[FolderPath])

  class Backend(t: BackendScope[Props, Props]) {
    def changePage(path: Option[String]): Unit = {
      t.props.routerCtl.set(FolderPath(UUID.fromString(t.props.oid), path)).unsafePerformIO()
      t.state.selected.set(None).unsafePerformIO()
    }
  }

  private[this] val CrumbLimit = 8

  val component = ReactComponentB[Props]("PathCrumb")
    .initialStateP(p => p)
    .backend(new Backend(_))
    .render { (p, s, b) =>

    def pathElement(path: Option[String], displayValue: ReactTag): ReactTag =
      <.a(^.onClick --> b.changePage(path))(displayValue)

    def pathElements(elems: Seq[String]): Seq[TagMod] = {
      var pb = Seq.newBuilder[String]
      val paths = elems.map { e =>
        if (e.nonEmpty) {
          pb += e
          val curr = pb.result()
          Some(curr.mkString("/", "/", ""))
        } else None
      }.takeRight(CrumbLimit).filter(_.nonEmpty)
      paths.zipWithIndex.map(path =>
        if (paths.size == CrumbLimit && path._2 == 0) pathElement(path._1, <.div("..."))
        else pathElement(path._1, <.div(path._1.map(_.stripPrefix("/"))))
      )
    }

    val pElems: Seq[String] = p.path.stripPrefix("/root/").stripPrefix("/").stripSuffix("/").split("/")

    <.div(Style.crumb)(
      if (pElems.nonEmpty) pathElement(None, <.div(<.i(FontAwesome.hddDrive))).compose(pathElements(pElems))
      else pathElement(None, <.div(<.i(FontAwesome.hddDrive)))
    )
  }.build

  def apply(p: Props) = component(p)

  def apply(oid: String, path: String, selected: ExternalVar[Option[File]], ctl: RouterCtl[FolderPath]) = component(Props(oid, path, selected, ctl))

}
