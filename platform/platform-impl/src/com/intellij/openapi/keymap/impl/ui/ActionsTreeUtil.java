// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.keymap.impl.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.actionMacro.ActionMacro;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.ui.search.SearchUtil;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionManagerEx;
import com.intellij.openapi.actionSystem.ex.QuickList;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.keymap.KeyMapBundle;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapExtension;
import com.intellij.openapi.keymap.KeymapGroup;
import com.intellij.openapi.keymap.ex.KeymapManagerEx;
import com.intellij.openapi.keymap.impl.ActionShortcutRestrictions;
import com.intellij.openapi.keymap.impl.KeymapImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.*;

public class ActionsTreeUtil {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.keymap.impl.ui.ActionsTreeUtil");

  public static final String MAIN_MENU_TITLE = KeyMapBundle.message("main.menu.action.title");
  public static final String MAIN_TOOLBAR = KeyMapBundle.message("main.toolbar.title");
  public static final String EDITOR_POPUP = KeyMapBundle.message("editor.popup.menu.title");

  public static final String EDITOR_TAB_POPUP = KeyMapBundle.message("editor.tab.popup.menu.title");
  public static final String FAVORITES_POPUP = KeyMapBundle.message("favorites.popup.title");
  public static final String PROJECT_VIEW_POPUP = KeyMapBundle.message("project.view.popup.menu.title");
  public static final String COMMANDER_POPUP = KeyMapBundle.message("commender.view.popup.menu.title");
  public static final String J2EE_POPUP = KeyMapBundle.message("j2ee.view.popup.menu.title");

  @NonNls
  private static final String EDITOR_PREFIX = "Editor";
  @NonNls private static final String TOOL_ACTION_PREFIX = "Tool_";

  private ActionsTreeUtil() {
  }

  public static Map<String, String> createPluginActionsMap() {
    Set<PluginId> visited = ContainerUtil.newHashSet();
    Map<String, String> result = ContainerUtil.newHashMap();
    for (IdeaPluginDescriptor descriptor : PluginManagerCore.getPlugins()) {
      PluginId id = descriptor.getPluginId();
      visited.add(id);
      if (PluginManagerCore.CORE_PLUGIN_ID.equals(id.getIdString())) continue;
      for (String actionId : ActionManagerEx.getInstanceEx().getPluginActions(id)) {
        result.put(actionId, descriptor.getName());
      }
    }
    for (PluginId id : PluginId.getRegisteredIds().values()) {
      if (visited.contains(id)) continue;
      for (String actionId : ActionManagerEx.getInstanceEx().getPluginActions(id)) {
        result.put(actionId, id.getIdString());
      }
    }
    return result;
  }

  private static Group createPluginsActionsGroup(Condition<? super AnAction> filtered) {
    Group pluginsGroup = new Group(KeyMapBundle.message("plugins.group.title"), null, null);
    final KeymapManagerEx keymapManager = KeymapManagerEx.getInstanceEx();
    ActionManagerEx managerEx = ActionManagerEx.getInstanceEx();
    final List<IdeaPluginDescriptor> plugins = new ArrayList<>();
    Collections.addAll(plugins, PluginManagerCore.getPlugins());
    Collections.sort(plugins, Comparator.comparing(IdeaPluginDescriptor::getName));

    List<PluginId> collected = new ArrayList<>();
    for (IdeaPluginDescriptor plugin : plugins) {
      collected.add(plugin.getPluginId());
      Group pluginGroup;
      if (plugin.getName().equals("IDEA CORE")) {
        continue;
      }
      else {
        pluginGroup = new Group(plugin.getName(), null, null);
      }
      final String[] pluginActions = managerEx.getPluginActions(plugin.getPluginId());
      if (pluginActions.length == 0) {
        continue;
      }
      Arrays.sort(pluginActions, Comparator.comparing(ActionsTreeUtil::getTextToCompare));
      for (String pluginAction : pluginActions) {
        if (keymapManager.getBoundActions().contains(pluginAction)) continue;
        final AnAction anAction = managerEx.getActionOrStub(pluginAction);
        if (filtered == null || filtered.value(anAction)) {
          pluginGroup.addActionId(pluginAction);
        }
      }
      if (pluginGroup.getSize() > 0) {
        pluginsGroup.addGroup(pluginGroup);
      }
    }

    for (PluginId pluginId : PluginId.getRegisteredIds().values()) {
      if (collected.contains(pluginId)) continue;
      Group pluginGroup = new Group(pluginId.getIdString(), null, null);
      final String[] pluginActions = managerEx.getPluginActions(pluginId);
      if (pluginActions.length == 0) {
        continue;
      }
      for (String pluginAction : pluginActions) {
        if (keymapManager.getBoundActions().contains(pluginAction)) continue;
        final AnAction anAction = managerEx.getActionOrStub(pluginAction);
        if (filtered == null || filtered.value(anAction)) {
          pluginGroup.addActionId(pluginAction);
        }
      }
      if (pluginGroup.getSize() > 0) {
        pluginsGroup.addGroup(pluginGroup);
      }
    }

    return pluginsGroup;
  }

  private static Group createMainMenuGroup(Condition<AnAction> filtered) {
    Group group = new Group(MAIN_MENU_TITLE, IdeActions.GROUP_MAIN_MENU, AllIcons.Nodes.KeymapMainMenu);
    ActionGroup mainMenuGroup = (ActionGroup)ActionManager.getInstance().getActionOrStub(IdeActions.GROUP_MAIN_MENU);
    fillGroupIgnorePopupFlag(mainMenuGroup, group, filtered);
    return group;
  }

  @Nullable
  private static Condition<AnAction> wrapFilter(@Nullable final Condition<? super AnAction> filter, final Keymap keymap, final ActionManager actionManager) {
    final ActionShortcutRestrictions shortcutRestrictions = ActionShortcutRestrictions.getInstance();
    return action -> {
      if (action == null) return false;
      final String id = action instanceof ActionStub ? ((ActionStub)action).getId() : actionManager.getId(action);
      if (id != null) {
        if (!Registry.is("keymap.show.alias.actions")) {
          String binding = getActionBinding(keymap, id);
          boolean bound = binding != null
                          && actionManager.getAction(binding) != null // do not hide bound action, that miss the 'bound-with'
                          && !hasAssociatedShortcutsInHierarchy(id, keymap); // do not hide bound actions when they are redefined
          if (bound) {
            return false;
          }
        }
        if (!shortcutRestrictions.getForActionId(id).allowChanging) {
          return false;
        }
      }

      return filter == null || filter.value(action);
    };
  }

  private static boolean hasAssociatedShortcutsInHierarchy(String id, Keymap keymap) {
    while (keymap != null) {
      if (((KeymapImpl)keymap).hasOwnActionId(id)) return true;
      keymap = keymap.getParent();
    }
    return false;
  }

  private static void fillGroupIgnorePopupFlag(ActionGroup actionGroup, Group group, Condition<AnAction> filtered) {
    AnAction[] mainMenuTopGroups = getActions(actionGroup);
    for (AnAction action : mainMenuTopGroups) {
      if (!(action instanceof ActionGroup)) continue;
      Group subGroup = createGroup((ActionGroup)action, false, filtered);
      if (subGroup.getSize() > 0) {
        group.addGroup(subGroup);
      }
    }
  }

  public static Group createGroup(ActionGroup actionGroup, boolean ignore, Condition<AnAction> filtered) {
    return createGroup(actionGroup, getName(actionGroup), actionGroup.getTemplatePresentation().getIcon(), null, ignore, filtered);
  }

  private static String getName(AnAction action) {
    final String name = action.getTemplatePresentation().getText();
    if (name != null && !name.isEmpty()) {
      return name;
    }
    else {
      final String id = action instanceof ActionStub ? ((ActionStub)action).getId() : ActionManager.getInstance().getId(action);
      if (id != null) {
        return id;
      }
      if (action instanceof DefaultActionGroup) {
        final DefaultActionGroup group = (DefaultActionGroup)action;
        if (group.getChildrenCount() == 0) return "Empty group";
        final AnAction[] children = group.getChildActionsOrStubs();
        for (AnAction child : children) {
          if (!(child instanceof Separator)) {
            return "group." + getName(child);
          }
        }
        return "Empty unnamed group";
      }
      return action.getClass().getName();
    }
  }

  public static Group createGroup(ActionGroup actionGroup,
                                  String groupName,
                                  Icon icon,
                                  Icon openIcon,
                                  boolean ignore,
                                  Condition<AnAction> filtered) {
    return createGroup(actionGroup, groupName, icon, openIcon, ignore, filtered, true);
  }

  public static Group createGroup(ActionGroup actionGroup, String groupName, Icon icon, Icon openIcon, boolean ignore, Condition<AnAction> filtered,
                                  boolean normalizeSeparators) {
    ActionManager actionManager = ActionManager.getInstance();
    Group group = new Group(groupName, actionManager.getId(actionGroup), icon);
    AnAction[] children = getActions(actionGroup);

    for (AnAction action : children) {
      if (action == null) {
        LOG.error(groupName + " contains null actions");
        continue;
      }
      if (action instanceof ActionGroup) {
        Group subGroup = createGroup((ActionGroup)action, getName(action), null, null, ignore, filtered, normalizeSeparators);
        if (!ignore && !((ActionGroup)action).isPopup()) {
          group.addAll(subGroup);
        }
        else if (subGroup.getSize() > 0 ||
                 filtered == null || filtered.value(action)) {
          group.addGroup(subGroup);
        }
      }
      else if (action instanceof Separator) {
        group.addSeparator();
      }
      else {
        String id = action instanceof ActionStub ? ((ActionStub)action).getId() : actionManager.getId(action);
        if (id != null) {
          if (id.startsWith(TOOL_ACTION_PREFIX)) continue;
          if (filtered == null || filtered.value(action)) {
            group.addActionId(id);
          }
        }
      }
    }
    if (normalizeSeparators) group.normalizeSeparators();
    return group;
  }

  private static Group createEditorActionsGroup(Condition<? super AnAction> filtered) {
    ActionManager actionManager = ActionManager.getInstance();
    DefaultActionGroup editorGroup = (DefaultActionGroup)actionManager.getActionOrStub(IdeActions.GROUP_EDITOR);
    ArrayList<String> ids = new ArrayList<>();

    addEditorActions(filtered, editorGroup, ids);

    Collections.sort(ids);
    Group group = new Group(KeyMapBundle.message("editor.actions.group.title"), IdeActions.GROUP_EDITOR, AllIcons.Nodes.KeymapEditor
    );
    for (String id : ids) {
      group.addActionId(id);
    }

    return group;
  }

  @Nullable
  private static String getActionBinding(final Keymap keymap, final String id) {
    if (keymap == null) return null;

    Keymap parent = keymap.getParent();
    String result = KeymapManagerEx.getInstanceEx().getActionBinding(id);
    if (result == null && parent != null) {
      result = KeymapManagerEx.getInstanceEx().getActionBinding(id);
    }
    return result;
  }

  private static void addEditorActions(final Condition<? super AnAction> filtered,
                                       final DefaultActionGroup editorGroup,
                                       final ArrayList<? super String> ids) {
    AnAction[] editorActions = editorGroup.getChildActionsOrStubs();
    final ActionManager actionManager = ActionManager.getInstance();
    for (AnAction editorAction : editorActions) {
      if (editorAction instanceof DefaultActionGroup) {
        addEditorActions(filtered, (DefaultActionGroup) editorAction, ids);
      }
      else {
        String actionId = editorAction instanceof ActionStub ? ((ActionStub)editorAction).getId() : actionManager.getId(editorAction);
        if (actionId == null) continue;
        if (filtered == null || filtered.value(editorAction)) {
          ids.add(actionId);
        }
      }
    }
  }

  private static Group createExtensionGroup(Condition<AnAction> filtered, final Project project, KeymapExtension provider) {
    return (Group) provider.createGroup(filtered, project);
  }

  private static Group createMacrosGroup(Condition<? super AnAction> filtered) {
    final ActionManagerEx actionManager = ActionManagerEx.getInstanceEx();
    String[] ids = actionManager.getActionIds(ActionMacro.MACRO_ACTION_PREFIX);
    Arrays.sort(ids);
    Group group = new Group(KeyMapBundle.message("macros.group.title"), null, null);
    for (String id : ids) {
      if (filtered == null || filtered.value(actionManager.getActionOrStub(id))) {
        group.addActionId(id);
      }
    }
    return group;
  }

  private static Group createQuickListsGroup(final Condition<? super AnAction> filtered, final String filter, final boolean forceFiltering, final QuickList[] quickLists) {
    Arrays.sort(quickLists, Comparator.comparing(QuickList::getActionId));

    Group group = new Group(KeyMapBundle.message("quick.lists.group.title"), null, null);
    for (QuickList quickList : quickLists) {
      if (filtered != null && filtered.value(ActionManagerEx.getInstanceEx().getAction(quickList.getActionId())) ||
          SearchUtil.isComponentHighlighted(quickList.getName(), filter, forceFiltering, null) ||
          filtered == null && StringUtil.isEmpty(filter)) {
        group.addQuickList(quickList);
      }
    }
    return group;
  }

  @NotNull
  private static Group createOtherGroup(@Nullable Condition<AnAction> filtered, Group addedActions, @Nullable Keymap keymap) {
    addedActions.initIds();
    Set<String> result = new THashSet<>();

    ActionManagerEx actionManager = ActionManagerEx.getInstanceEx();
    if (keymap != null) {
      for (String id : keymap.getActionIdList()) {
        if (id.startsWith(EDITOR_PREFIX) && actionManager.getActionOrStub("$" + id.substring(6)) != null) {
          continue;
        }

        if (!id.startsWith(QuickList.QUICK_LIST_PREFIX) && !addedActions.containsId(id)) {
          result.add(id);
        }
      }
    }

    // add all registered actions
    final KeymapManagerEx keymapManager = KeymapManagerEx.getInstanceEx();
    String[] registeredActionIds = actionManager.getActionIds("");
    for (String id : registeredActionIds) {
      final AnAction actionOrStub = actionManager.getActionOrStub(id);
      if (actionOrStub instanceof ActionGroup && !((ActionGroup)actionOrStub).canBePerformed(DataManager.getInstance().getDataContext())) {
        continue;
      }
      if (id.startsWith(QuickList.QUICK_LIST_PREFIX) ||
          addedActions.containsId(id) ||
          result.contains(id) ||
          keymapManager.getBoundActions().contains(id)) {
        continue;
      }

      result.add(id);
    }

    filterOtherActionsGroup(result);

    Group group = new Group(KeyMapBundle.message("other.group.title"), AllIcons.Nodes.KeymapOther);

    AnAction[] groupedActions = getActions("Other.KeymapGroup");
    for (AnAction action : groupedActions) {
      addAction(group, action, filtered);
    }
    result.removeAll(group.initIds());

    for (String id : ContainerUtil.sorted(result, (id1, id2) -> getTextToCompare(id1).compareToIgnoreCase(getTextToCompare(id2)))) {
      if (filtered == null || filtered.value(actionManager.getActionOrStub(id))) group.addActionId(id);
    }
    return group;
  }

  private static String getTextToCompare(String id) {
    AnAction action = ActionManager.getInstance().getActionOrStub(id);
    if (action == null) {
      return id;
    }
    String text = action.getTemplatePresentation().getText();
    return text != null ? text : id;
  }

  private static void filterOtherActionsGroup(Set<String> actions) {
    filterOutGroup(actions, IdeActions.GROUP_GENERATE);
    filterOutGroup(actions, IdeActions.GROUP_NEW);
    filterOutGroup(actions, IdeActions.GROUP_CHANGE_SCHEME);
  }

  private static void filterOutGroup(Set<String> actions, String groupId) {
    if (groupId == null) {
      throw new IllegalArgumentException();
    }
    ActionManager actionManager = ActionManager.getInstance();
    AnAction action = actionManager.getActionOrStub(groupId);
    if (action instanceof DefaultActionGroup) {
      DefaultActionGroup group = (DefaultActionGroup)action;
      AnAction[] children = group.getChildActionsOrStubs();
      for (AnAction child : children) {
        String childId = child instanceof ActionStub ? ((ActionStub)child).getId() : actionManager.getId(child);
        if (childId == null) {
          // SCR 35149
          continue;
        }
        if (child instanceof DefaultActionGroup) {
          filterOutGroup(actions, childId);
        }
        else {
          actions.remove(childId);
        }
      }
    }
  }

  public static DefaultMutableTreeNode createNode(Group group) {
    DefaultMutableTreeNode node = new DefaultMutableTreeNode(group);
    for (Object child : group.getChildren()) {
      if (child instanceof Group) {
        DefaultMutableTreeNode childNode = createNode((Group)child);
        node.add(childNode);
      }
      else {
        LOG.assertTrue(child != null);
        node.add(new DefaultMutableTreeNode(child));
      }
    }
    return node;
  }

  public static Group createMainGroup(final Project project, final Keymap keymap, final QuickList[] quickLists) {
    return createMainGroup(project, keymap, quickLists, null, false, null);
  }

  public static Group createMainGroup(final Project project,
                                      final Keymap keymap,
                                      final QuickList[] quickLists,
                                      final String filter,
                                      final boolean forceFiltering,
                                      final Condition<AnAction> filtered) {
    final Condition<AnAction> wrappedFilter = wrapFilter(filtered, keymap, ActionManager.getInstance());
    Group mainGroup = new Group(KeyMapBundle.message("all.actions.group.title"), null, null);
    mainGroup.addGroup(createEditorActionsGroup(wrappedFilter));
    mainGroup.addGroup(createMainMenuGroup(wrappedFilter));
    for (KeymapExtension extension : KeymapExtension.EXTENSION_POINT_NAME.getExtensionList()) {
      final Group group = createExtensionGroup(wrappedFilter, project, extension);
      if (group != null) {
        mainGroup.addGroup(group);
      }
    }
    mainGroup.addGroup(createMacrosGroup(wrappedFilter));
    mainGroup.addGroup(createQuickListsGroup(wrappedFilter, filter, forceFiltering, quickLists));
    mainGroup.addGroup(createPluginsActionsGroup(wrappedFilter));
    mainGroup.addGroup(createOtherGroup(wrappedFilter, mainGroup, keymap));
    if (!StringUtil.isEmpty(filter) || filtered != null) {
      final ArrayList list = mainGroup.getChildren();
      for (Iterator i = list.iterator(); i.hasNext();) {
        final Object o = i.next();
        if (o instanceof Group) {
          final Group group = (Group)o;
          if (group.getSize() == 0) {
            if (!SearchUtil.isComponentHighlighted(group.getName(), filter, forceFiltering, null)) {
              i.remove();
            }
          }
        }
      }
    }
    return mainGroup;
  }

  public static Condition<AnAction> isActionFiltered(final String filter, final boolean force) {
    return action -> {
      if (filter == null) return true;
      if (action == null) return false;
      action = tryUnstubAction(action);

      final String insensitiveFilter = filter.toLowerCase();
      ArrayList<String> options = new ArrayList<>();
      options.add(action.getTemplatePresentation().getText());
      options.add(action.getTemplatePresentation().getDescription());
      String id = action instanceof ActionStub ? ((ActionStub)action).getId() : ActionManager.getInstance().getId(action);
      if (id != null) {
        options.add(id);
        options.addAll(AbbreviationManager.getInstance().getAbbreviations(id));
      }

      for (String text : options) {
        if (text != null) {
          final String lowerText = text.toLowerCase();

          if (SearchUtil.isComponentHighlighted(lowerText, insensitiveFilter, force, null) || lowerText.contains(insensitiveFilter)) {
            return true;
          }
        }
      }
      return false;
    };
  }

  public static Condition<AnAction> isActionFiltered(final ActionManager actionManager,
                                                     final Keymap keymap,
                                                     final Shortcut shortcut) {
    return action -> {
      if (shortcut == null) return true;
      if (action == null) return false;
      final Shortcut[] actionShortcuts =
        keymap.getShortcuts(action instanceof ActionStub ? ((ActionStub)action).getId() : actionManager.getId(action));
      for (Shortcut actionShortcut : actionShortcuts) {
        if (actionShortcut != null && actionShortcut.startsWith(shortcut)) {
          return true;
        }
      }
      return false;
    };
  }

  public static Condition<AnAction> isActionFiltered(final ActionManager actionManager,
                                                     final Keymap keymap,
                                                     final Shortcut shortcut,
                                                     final String filter,
                                                     final boolean force) {
    return filter != null && filter.length() > 0 ? isActionFiltered(filter, force) :
           shortcut != null ? isActionFiltered(actionManager, keymap, shortcut) : null;
  }

  public static void addAction(KeymapGroup group, AnAction action, Condition<AnAction> filtered) {
    addAction(group, action, filtered, false);
  }

  public static void addAction(KeymapGroup group, AnAction action, Condition<AnAction> filtered, boolean forceNonPopup) {
    if (action instanceof ActionGroup) {
      if (forceNonPopup) {
        AnAction[] actions = getActions((ActionGroup)action);
        for (AnAction childAction : actions) {
          addAction(group, childAction, filtered, true);
        }
      }
      else {
        Group subGroup = createGroup((ActionGroup)action, false, filtered);
        if (subGroup.getSize() > 0) {
          group.addGroup(subGroup);
        }
      }
    }
    else if (action instanceof Separator) {
      if (group instanceof Group) {
        ((Group)group).addSeparator();
      }
    }
    else {
      if (filtered == null || filtered.value(action)) {
        String id = action instanceof ActionStub ? ((ActionStub)action).getId() : ActionManager.getInstance().getId(action);
        group.addActionId(id);
      }
    }
  }

  public static AnAction[] getActions(String actionGroup) {
    return getActions((ActionGroup)ActionManager.getInstance().getActionOrStub(actionGroup));
  }

  public static AnAction[] getActions(ActionGroup group) {
    return group instanceof DefaultActionGroup
           ? ((DefaultActionGroup)group).getChildActionsOrStubs()
           : group.getChildren(null);
  }

  @NotNull
  private static AnAction tryUnstubAction(@NotNull AnAction action) {
    if (action instanceof ActionStub) {
      AnAction newAction = ActionManager.getInstance().getActionOrStub(((ActionStub)action).getId());
      if (newAction != null) return newAction;
    }
    return action;
  }
}
