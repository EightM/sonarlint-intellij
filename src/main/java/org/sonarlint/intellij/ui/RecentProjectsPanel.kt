package org.sonarlint.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.IdeBundle
import com.intellij.ide.PowerSaveMode
import com.intellij.ide.ProjectGroup
import com.intellij.ide.ProjectGroupActionGroup
import com.intellij.ide.RecentProjectListActionProvider
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.ReopenProjectAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.UniqueNameBuilder
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.impl.welcomeScreen.BottomLineBorder
import com.intellij.openapi.wm.impl.welcomeScreen.FlatWelcomeFrame
import com.intellij.ui.ClickListener
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.ListUtil
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.speedSearch.ListWithFilter
import com.intellij.util.IconUtil
import com.intellij.util.PathUtil
import com.intellij.util.SystemProperties
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.containers.toArray
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.accessibility.AccessibleContextUtil
import org.jetbrains.annotations.ApiStatus.ScheduledForRemoval
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.border.LineBorder
import kotlin.math.max

open class SonarLintRecentProjectPanel(private val onProjectSelected: (Project) -> Unit) : JPanel(BorderLayout()) {

    protected val myList: JBList<AnAction>
    private val myPathShortener: UniqueNameBuilder<ReopenProjectAction>
    protected var projectsWithLongPaths: Set<ReopenProjectAction> = HashSet()
    private var myChecker: FilePathChecker? = null
    private var myHoverIndex = -1

    private fun performSelectedAction(event: InputEvent, selection: AnAction): AnAction {
        val actionPlace = if (UIUtil.uiParents(myList, true).filter(FlatWelcomeFrame::class.java).isEmpty) ActionPlaces.POPUP else ActionPlaces.WELCOME_SCREEN
        val actionEvent = AnActionEvent
                .createFromInputEvent(event, actionPlace, selection.templatePresentation,
                        DataManager.getInstance().getDataContext(myList), false, false)
        ActionUtil.performActionDumbAwareWithCallbacks(selection, actionEvent, actionEvent.dataContext)

        // TODO project is nullable, need to handle this case
        actionEvent.project?.let { onProjectSelected(it) }
        return selection
    }

    protected fun isPathValid(path: String?): Boolean {
        return myChecker == null || myChecker!!.isValid(path)
    }

    private val preferredScrollableViewportSize: Dimension
        get() = JBUI.size(350, 250)

    protected fun addMouseMotionListener() {
        val mouseAdapter: MouseAdapter = object : MouseAdapter() {
            var myIsEngaged = false
            override fun mouseMoved(e: MouseEvent) {
                val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                if (focusOwner == null) {
                    IdeFocusManager.getGlobalInstance().doWhenFocusSettlesDown { IdeFocusManager.getGlobalInstance().requestFocus(myList, true) }
                }
                if (myList.selectedIndices.size > 1) {
                    return
                }
                if (!myIsEngaged || UIUtil.isSelectionButtonDown(e) || focusOwner is JRootPane) {
                    myIsEngaged = true
                    return
                }
                val point = e.point
                val index = myList.locationToIndex(point)
                myList.selectedIndex = index
                val cellBounds = myList.getCellBounds(index, index)
                if (cellBounds != null && cellBounds.contains(point)) {
                    UIUtil.setCursor(myList, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    myHoverIndex = index
                    myList.repaint(cellBounds)
                } else {
                    UIUtil.setCursor(myList, Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR))
                    myHoverIndex = -1
                    myList.repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                myHoverIndex = -1
                myList.repaint()
            }
        }
        myList.addMouseMotionListener(mouseAdapter)
        myList.addMouseListener(mouseAdapter)
    }

    protected fun createList(recentProjectActions: Array<AnAction>, size: Dimension): JBList<AnAction> {
        return (MyList(size, recentProjectActions) as JBList<AnAction>)
    }

    protected fun createRenderer(): ListCellRenderer<AnAction>? {
        return RecentProjectItemRenderer() as ListCellRenderer<AnAction>
    }

    protected fun createTitle(): JPanel? {
        val title: JPanel = object : JPanel() {
            override fun getPreferredSize(): Dimension {
                return Dimension(super.getPreferredSize().width, JBUIScale.scale(28))
            }
        }
        title.border = BottomLineBorder()
        val titleLabel = JLabel(RECENT_PROJECTS_LABEL)
        title.add(titleLabel)
        titleLabel.horizontalAlignment = SwingConstants.CENTER
        titleLabel.foreground = WelcomeScreenColors.CAPTION_FOREGROUND
        title.background = WelcomeScreenColors.CAPTION_BACKGROUND
        return title
    }

    private inner class MyList constructor(size: Dimension, listData: Array<AnAction>) : JBList<AnAction?>(*listData) {
        private val mySize: Dimension?
        private var myMousePoint: Point? = null
        fun getCloseIconRect(index: Int): Rectangle {
            val bounds = getCellBounds(index, index)
            val icon = toSize(AllIcons.Welcome.Project.Remove)
            return Rectangle(bounds.width - icon.iconWidth - JBUIScale.scale(10),
                    bounds.y + (bounds.height - icon.iconHeight) / 2,
                    icon.iconWidth, icon.iconHeight)
        }

        override fun paint(g: Graphics) {
            super.paint(g)
            if (myMousePoint != null) {
                val index = locationToIndex(myMousePoint)
                if (index != -1) {
                    val iconRect = getCloseIconRect(index)
                    val icon = toSize(if (iconRect.contains(myMousePoint)) AllIcons.Welcome.Project.Remove_hover else AllIcons.Welcome.Project.Remove)
                    icon.paintIcon(this, g, iconRect.x, iconRect.y)
                }
            }
        }

        override fun getToolTipText(event: MouseEvent): String? {
            val i = locationToIndex(event.point)
            if (i != -1) {
                val elem: Any = model.getElementAt(i)!!
                if (elem is ReopenProjectAction) {
                    val path = elem.projectPath
                    val valid = isPathValid(path)
                    if (!valid || projectsWithLongPaths.contains(elem)) {
                        val suffix = if (valid) "" else " (unavailable)"
                        return PathUtil.toSystemDependentName(path) + suffix
                    }
                }
            }
            return super.getToolTipText(event)
        }

        override fun getPreferredScrollableViewportSize(): Dimension {
            return mySize ?: super.getPreferredScrollableViewportSize()
        }

        inner class MouseHandler : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                myMousePoint = e.point
            }

            override fun mouseExited(e: MouseEvent) {
                myMousePoint = null
            }

            override fun mouseMoved(e: MouseEvent) {
                myMousePoint = e.point
            }

            override fun mouseReleased(e: MouseEvent) {
                val point = e.point
                val index = locationToIndex(point)
                if (index == -1 || !getCloseIconRect(index).contains(point)) {
                    return
                }
                e.consume()
                val element: Any = model.getElementAt(index)!!
                if (element is ProjectGroupActionGroup) {
                    val group = element.group
                    if (group.isTutorials) {
                        removeTutorialChildren(group)
                    }
                }
                removeRecentProjectElement(element)
                ListUtil.removeSelectedItems<AnAction>(this@MyList)
            }

            private fun removeTutorialChildren(group: ProjectGroup) {
                var currentIndex = this@MyList.selectedIndex
                val projects = group.projects
                val childIndices = IntArray(projects.size)
                for (i in projects.indices) {
                    childIndices[i] = currentIndex + 1
                    currentIndex++
                }
                ListUtil.removeIndices<AnAction>(this@MyList, childIndices)
                ApplicationManager.getApplication().invokeLater {
                    val title = "Tutorials have been removed from the recent list"
                    val content = "You can still find them in the Help menu"
                    Notifications.Bus.notify(Notification(IdeBundle.message("notification.group.tutorials"), title, content, NotificationType.INFORMATION))
                }
            }
        }

        init {
            mySize = size
            setExpandableItemsEnabled(false)
            setEmptyText(IdeBundle.message("empty.text.no.project.open.yet"))
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            getAccessibleContext().accessibleName = RECENT_PROJECTS_LABEL
            val handler = MouseHandler()
            addMouseListener(handler)
            addMouseMotionListener(handler)
        }
    }

    protected inner class RecentProjectItemRenderer : JPanel(VerticalFlowLayout()), ListCellRenderer<AnAction?> {
        private val myName = JLabel()
        private val myPath = JLabel()
        private var myHovered = false

        @ScheduledForRemoval(inVersion = "2020.2")
        @Deprecated("use the default constructor ")

        private fun layoutComponents() {
            add(myName)
            add(myPath)
        }

        private fun getListBackground(isSelected: Boolean): Color {
            return UIUtil.getListBackground(isSelected, true)
        }

        private fun getListForeground(isSelected: Boolean): Color {
            return UIUtil.getListForeground(isSelected, true)
        }

        override fun getListCellRendererComponent(list: JList<out AnAction?>, value: AnAction?, index: Int, selected: Boolean, focused: Boolean): Component {
            myHovered = myHoverIndex == index
            val fore = getListForeground(selected)
            val back = getListBackground(selected)
            myName.foreground = fore
            myPath.foreground = if (selected) fore else UIUtil.getInactiveTextColor()
            background = back
            if (value is ReopenProjectAction) {
                myName.text = value.templatePresentation.text
                myPath.text = getTitle2Text(value, myPath, JBUIScale.scale(40))
            } else if (value is ProjectGroupActionGroup) {
                myName.text = value.group.name
                myPath.text = ""
            }
            AccessibleContextUtil.setCombinedName(this, myName, " - ", myPath)
            AccessibleContextUtil.setCombinedDescription(this, myName, " - ", myPath)
            return this
        }

        private fun getTitle2Text(action: ReopenProjectAction, pathLabel: JComponent, leftOffset: Int): String? {
            var fullText = action.projectPath
            if (fullText == null || fullText.isEmpty()) return " "
            fullText = FileUtil.getLocationRelativeToUserHome(PathUtil.toSystemDependentName(fullText), false)
            try {
                val fm = pathLabel.getFontMetrics(pathLabel.font)
                val maxWidth = this.width - leftOffset - ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE.getWidth().toInt() -
                        JBUIScale.scale(10)
                if (maxWidth > 0 && fm.stringWidth(fullText) > maxWidth) {
                    return truncateDescription(fullText, fm, maxWidth)
                }
            } catch (e: Exception) {
                LOG.error("Path label font: " + pathLabel.font)
                LOG.error("Panel width: " + this.width)
                LOG.error(e)
            }
            return fullText
        }


        private fun truncateDescription(fullText: String?, fm: FontMetrics, maxWidth: Int): String {
            var left = 1
            var right = 1
            val center = fullText!!.length / 2
            var s = fullText.substring(0, center - left) + "..." + fullText.substring(center + right)
            while (fm.stringWidth(s) > maxWidth) {
                if (left == right) {
                    left++
                } else {
                    right++
                }
                if (center - left < 0 || center + right >= fullText.length) {
                    return ""
                }
                s = fullText.substring(0, center - left) + "..." + fullText.substring(center + right)
            }
            return s
        }

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return Dimension(Math.min(size.width, JBUIScale.scale(245)), size.height)
        }

        override fun getSize(): Dimension {
            return preferredSize
        }

        init {
            myPath.font = JBUI.Fonts.label(if (SystemInfo.isMac) 10f else 11f)
            isFocusable = true
            layoutComponents()
        }
    }

    class FilePathChecker internal constructor(private val myCallback: Runnable, private val myPaths: Collection<String>) : Disposable, ApplicationActivationListener, PowerSaveMode.Listener {
        private var myService: ScheduledExecutorService? = null
        private val myInvalidPaths = Collections.synchronizedSet(HashSet<String>())
        fun isValid(path: String?): Boolean {
            return !myInvalidPaths.contains(path)
        }

        override fun applicationActivated(ideFrame: IdeFrame) {
            onAppStateChanged()
        }

        override fun delayedApplicationDeactivated(ideFrame: Window) {
            onAppStateChanged()
        }

        override fun applicationDeactivated(ideFrame: IdeFrame) {}
        override fun powerSaveStateChanged() {
            onAppStateChanged()
        }

        private fun onAppStateChanged() {
            val settingsAreOK = Registry.`is`("autocheck.availability.welcome.screen.projects") && !PowerSaveMode.isEnabled()
            val everythingIsOK = settingsAreOK && ApplicationManager.getApplication().isActive
            if (myService == null && everythingIsOK) {
                myService = AppExecutorUtil.createBoundedScheduledExecutorService("CheckRecentProjectPaths Service", 2)
                for (path in myPaths) {
                    scheduleCheck(path, 0)
                }
                ApplicationManager.getApplication().invokeLater(myCallback)
            }
            if (myService != null && !everythingIsOK) {
                if (!settingsAreOK) {
                    myInvalidPaths.clear()
                }
                if (!myService!!.isShutdown) {
                    myService!!.shutdown()
                    myService = null
                }
                ApplicationManager.getApplication().invokeLater(myCallback)
            }
        }

        override fun dispose() {
            if (myService != null) {
                myService!!.shutdownNow()
            }
        }

        private fun scheduleCheck(path: String, delay: Long) {
            if (myService == null || myService!!.isShutdown) return
            myService!!.schedule({
                val startTime = System.currentTimeMillis()
                val pathIsValid  = try {
                    !RecentProjectsManagerBase.isFileSystemPath(path) || isPathAvailable(path)
                } catch (e: Exception) {
                    false
                }
                if (myInvalidPaths.contains(path) == pathIsValid) {
                    if (pathIsValid) {
                        myInvalidPaths.remove(path)
                    } else {
                        myInvalidPaths.add(path)
                    }
                    ApplicationManager.getApplication().invokeLater(myCallback)
                }
                scheduleCheck(path, max(MIN_AUTO_UPDATE_MILLIS.toLong(), 10 * (System.currentTimeMillis() - startTime)))
            }, delay, TimeUnit.MILLISECONDS)
        }

        companion object {
            private const val MIN_AUTO_UPDATE_MILLIS = 2500
        }

        init {
            val connection = ApplicationManager.getApplication().messageBus.connect(this)
            connection.subscribe(ApplicationActivationListener.TOPIC, this)
            connection.subscribe(PowerSaveMode.TOPIC, this)
            onAppStateChanged()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SonarLintRecentProjectPanel::class.java)
        const val RECENT_PROJECTS_LABEL = "Recent Projects"
        protected fun removeRecentProjectElement(element: Any) {
            val manager = RecentProjectsManager.getInstance()
            if (element is ReopenProjectAction) {
                manager.removePath(element.projectPath)
            } else if (element is ProjectGroupActionGroup) {
                manager.removeGroup(element.group)
            }
        }

        private fun isPathAvailable(pathStr: String): Boolean {
            val path = Paths.get(pathStr)
            val pathRoot = path.root ?: return false
            if (SystemInfo.isWindows && pathRoot.toString().startsWith("\\\\")) return true
            for (fsRoot in pathRoot.fileSystem.rootDirectories) {
                if (pathRoot == fsRoot) return Files.exists(path)
            }
            return false
        }

        private fun toSize(icon: Icon): Icon {
            return IconUtil.toSize(icon,
                    ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE.getWidth().toInt(),
                    ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE.getHeight().toInt())
        }
    }

    init {
        val recentProjectActions = RecentProjectListActionProvider.getInstance().getActions()
        myPathShortener = UniqueNameBuilder(SystemProperties.getUserHome(), File.separator, 40)
        val pathsToCheck: MutableCollection<String> = HashSet()
        for (action in recentProjectActions) {
            if (action is ReopenProjectAction) {
                myPathShortener.addPath(action, action.projectPath)
                pathsToCheck.add(action.projectPath)
            }
        }
        myList = createList(recentProjectActions.toArray(AnAction.EMPTY_ARRAY), preferredScrollableViewportSize)
        myList.cellRenderer = createRenderer()!!
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                val selectedIndex = myList.selectedIndex
                if (selectedIndex >= 0) {
                    val cellBounds = myList.getCellBounds(selectedIndex, selectedIndex)
                    if (cellBounds.contains(event.point)) {
                        val selection = myList.selectedValue
                        if (selection != null) {
                            performSelectedAction(event, selection)
                        }
                    }
                }
                return true
            }
        }.installOn(myList)
        myList.registerKeyboardAction({ e ->
            val selectedValues = myList.selectedValuesList
            if (selectedValues != null) {
                for (selectedAction in selectedValues) {
                    if (selectedAction != null) {
                        val event: InputEvent = KeyEvent(myList, KeyEvent.KEY_PRESSED, e.getWhen(), e.modifiers, KeyEvent.VK_ENTER, '\r')
                        performSelectedAction(event, selectedAction)
                    }
                }
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        addMouseMotionListener()
        myList.selectedIndex = 0
        val scroll = JBScrollPane(myList, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
        scroll.border = null
        val list = if (recentProjectActions.isEmpty()) myList else ListWithFilter.wrap(myList, scroll) { o: AnAction ->
            if (o is ReopenProjectAction) {
                val home = SystemProperties.getUserHome()
                var path = o.projectPath
                if (FileUtil.startsWith(path, home)) {
                    path = path.substring(home.length)
                }
                return@wrap o.projectName + " " + path
            } else if (o is ProjectGroupActionGroup) {
                return@wrap o.group.name
            }
            o.toString()
        }
        add(list, BorderLayout.CENTER)
        val title = createTitle()
        if (title != null) {
            add(title, BorderLayout.NORTH)
        }
        border = LineBorder(WelcomeScreenColors.BORDER_COLOR)
    }
}

object WelcomeScreenColors {
    // These two for the topmost "Welcome to <product name>"
    val WELCOME_HEADER_BACKGROUND: Color = JBColor.namedColor("WelcomeScreen.headerBackground", JBColor(Gray._220, Gray._75))
    val WELCOME_HEADER_FOREGROUND: Color = JBColor.namedColor("WelcomeScreen.headerForeground", JBColor(Gray._80, Gray._197))

    // This is for border around recent projects, action cards and also lines separating header and footer from main contents.
    val BORDER_COLOR: Color = JBColor.namedColor("WelcomeScreen.borderColor", JBColor(Gray._190, Gray._85))

    // This is for circle around hovered (next) icon
    val GROUP_ICON_BORDER_COLOR: Color = JBColor.namedColor("WelcomeScreen.groupIconBorderColor", JBColor(Gray._190, Gray._55))

    // These two are for footer (Full product with build #, small letters)
    val FOOTER_BACKGROUND: Color = JBColor.namedColor("WelcomeScreen.footerBackground", JBColor(Gray._210, Gray._75))
    val FOOTER_FOREGROUND: Color = JBColor.namedColor("WelcomeScreen.footerForeground", JBColor(Gray._0, Gray._197))

    // There two are for caption of Recent Project and Action Cards
    val CAPTION_BACKGROUND: Color = JBColor.namedColor("WelcomeScreen.captionBackground", JBColor(Gray._210, Gray._75))
    val CAPTION_FOREGROUND: Color = JBColor.namedColor("WelcomeScreen.captionForeground", JBColor(Gray._0, Gray._197))
}
