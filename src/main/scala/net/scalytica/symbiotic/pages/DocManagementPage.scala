package net.scalytica.symbiotic.pages

import japgolly.scalajs.react.MonocleReact._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.ExternalVar
import japgolly.scalajs.react.extra.router2.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import monocle.macros._
import net.scalytica.symbiotic.components.dman.{FileInfo, FolderContent, FolderTree}
import net.scalytica.symbiotic.css.Material
import net.scalytica.symbiotic.models.dman.FileWrapper
import net.scalytica.symbiotic.routes.DMan.FolderPath

import scalacss.Defaults._
import scalacss.ScalaCssReact._

object DocManagementPage {

  object Style extends StyleSheet.Inline {

    import dsl._

    val container = Material.row.compose(style(
      display.flex,
      height(100.%%),
      width(100.%%)
    ))

    val tree = style(
      Material.col,
      addClassName("s3"),
      display.flex,
      height(100.%%),
      overflow.scroll
    )

    val content = style(
      Material.col,
      addClassNames("s7"),
      display.flex,
      height(100.%%)
    )

    val preview = style(
      Material.col,
      addClassNames("s2"),
      display.flex,
      height(100.%%)
    )
  }

  @Lenses
  case class Props(
    customerId: String,
    projectId: String,
    selectedFolder: Option[String],
    selectedFile: Option[FileWrapper],
    ctl: RouterCtl[FolderPath])

  val component = ReactComponentB[Props]("DocumentManagement")
    .initialStateP(p => p)
    .render { $ =>
    val sf = ExternalVar.state($.focusStateL(Props.selectedFile))
    <.div(Style.container,
      <.div(Style.tree, FolderTree($.props.customerId, $.props.projectId, $.props.selectedFolder, $.props.ctl)),
      <.div(Style.content, FolderContent($.props.customerId, $.props.selectedFolder, Nil, sf, $.props.ctl)),
      // TODO: content should reference info component...to be able to change its state
      <.div(Style.preview, FileInfo(sf))
    )
  }.build

  def apply(p: Props): ReactComponentU[Props, Props, Unit, TopNode] = component(p)

  def apply(cid: String, pid: String, ctl: RouterCtl[FolderPath]): ReactComponentU[Props, Props, Unit, TopNode] =
    component(Props(cid, pid, None, None, ctl))

  def apply(cid: String, pid: String, sf: Option[String], ctl: RouterCtl[FolderPath]): ReactComponentU[Props, Props, Unit, TopNode] =
    component(Props(cid, pid, sf, None, ctl))
}
