package net.scalytica.symbiotic.components.dman.foldertree

import japgolly.scalajs.react.extra.ExternalVar
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{ReactComponentB, _}
import net.scalytica.symbiotic.css.FileTypes.{Folder, FolderOpen}
import net.scalytica.symbiotic.css.{FileTypes, GlobalStyle}
import net.scalytica.symbiotic.models.FileId
import net.scalytica.symbiotic.models.dman.{ManagedFile, FTree, FolderItem}
import net.scalytica.symbiotic.routing.DMan.FolderURIElem

import scalacss.Defaults._
import scalacss.ScalaCssReact._

object FolderTreeItem {

  object Style extends StyleSheet.Inline {

    import dsl._

    val children = styleF.bool(
      expanded =>
        styleS(
          cursor pointer,
          textDecoration := "none",
          mixinIfElse(expanded)(
            display contents
          )(display none)
      )
    )

    val folderWrapper = style(
      display inlineFlex,
      lineHeight(2 em),
      mixin(&.hover(backgroundColor rgb (222, 222, 222)))
    )

    val folder = styleF.bool(
      expanded =>
        styleS(
          color steelblue,
          mixinIfElse(expanded)(
            FileTypes.Styles.Icon2x(FolderOpen)
          )(FileTypes.Styles.Icon2x(Folder))
      )
    )

    val folderName = style(
      color darkslategrey,
      marginLeft(5 px),
      fontSize(16 px),
      mixin(
        &.hover(
          textDecoration := "none"
        )
      )
    )
  }

  case class Props(
      fi: FolderItem,
      selectedFolderId: ExternalVar[Option[FileId]],
      selectedFile: ExternalVar[Option[ManagedFile]],
      expanded: Boolean,
      ctl: RouterCtl[FolderURIElem]
  )

  class Backend($ : BackendScope[Props, Props]) {

    def expandCollapse(e: ReactEventI): Callback =
      $.modState(s => s.copy(expanded = !s.expanded))

    def changeFolder(e: ReactEventI): Callback =
      $.state.flatMap { s =>
        $.props.flatMap { p =>
          s.selectedFile.set(None) >>
            s.selectedFolderId.set(Option(p.fi.folderId)) >>
            s.ctl.set(FolderURIElem(Option(p.fi.folderId.toUUID)))
        }
      }

    def render(p: Props, s: Props) = {
      <.li(
        <.div(
          Style.folderWrapper,
          <.i(Style.folder(s.expanded), ^.onClick ==> expandCollapse),
          <.a(Style.folderName, ^.onClick ==> changeFolder, s" ${p.fi.name}")
        ),
        <.div(
          Style.children(s.expanded),
          <.ul(
            GlobalStyle.ulStyle(false),
            ^.listStyle := "none",
            p.fi.children.map(
              fi =>
                FolderTreeItem(fi, p.selectedFolderId, p.selectedFile, p.ctl)
            )
          )
        )
      )
    }
  }

  val component = ReactComponentB[Props]("FolderTreeItem")
    .initialState_P(p => p)
    .renderBackend[Backend]
    .build

  // ===============  Constructors ===============

  type FolderTreeItemComponent =
    ReactComponentU[Props, Props, Backend, TopNode]

  def apply(p: Props): FolderTreeItemComponent = component(p)

  def apply(
      fi: FolderItem,
      sfolderId: ExternalVar[Option[FileId]],
      selectedFile: ExternalVar[Option[ManagedFile]],
      ctl: RouterCtl[FolderURIElem]
  ): FolderTreeItemComponent = {
    if (fi.path == FTree.rootFolder)
      component(Props(fi, sfolderId, selectedFile, expanded = true, ctl))
    else component(Props(fi, sfolderId, selectedFile, expanded = false, ctl))
  }

}
