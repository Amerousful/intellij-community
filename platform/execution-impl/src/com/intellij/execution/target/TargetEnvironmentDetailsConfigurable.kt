// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.target

import com.intellij.execution.ExecutionBundle
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.NamedConfigurable
import com.intellij.openapi.util.NlsActions
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.layout.*
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.util.function.Consumer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

internal class TargetEnvironmentDetailsConfigurable(
  private val project: Project,
  private val config: TargetEnvironmentConfiguration,
  defaultLanguage: LanguageRuntimeType<*>?,
  treeUpdate: Runnable
) : NamedConfigurable<TargetEnvironmentConfiguration>(true, treeUpdate) {

  private val targetConfigurable: Configurable = config.getTargetType().createConfigurable(project, config, defaultLanguage)
  private val runtimeConfigurables = mutableListOf<Configurable>()

  override fun getBannerSlogan(): String = config.displayName

  override fun getIcon(expanded: Boolean): Icon = config.getTargetType().icon

  override fun isModified(): Boolean = allConfigurables().any { it.isModified }

  override fun getDisplayName(): String = config.displayName

  override fun apply() = allConfigurables().forEach { it.apply() }

  override fun setDisplayName(name: String) {
    config.displayName = name
  }

  override fun disposeUIResources() {
    super.disposeUIResources()
    allConfigurables().forEach { it.disposeUIResources() }
  }

  override fun getEditableObject() = config

  override fun createOptionsPanel(): JComponent {
    val panel = JPanel(VerticalLayout(JBUIScale.scale(UIUtil.DEFAULT_VGAP)))
    panel.border = JBUI.Borders.empty(0, 10, 10, 10)

    panel.add(targetConfigurable.createComponent() ?: throw IllegalStateException())

    config.runtimes.resolvedConfigs().forEach {
      panel.add(createRuntimePanel(config, it))
    }
    panel.add(createAddRuntimeHyperlink())
    return JBScrollPane(panel).also {
      it.border = JBUI.Borders.empty()
    }
  }

  private fun createRuntimePanel(target: TargetEnvironmentConfiguration, runtime: LanguageRuntimeConfiguration): JPanel {
    return panel {
      row {
        val separator = TitledSeparator(runtime.getRuntimeType().configurableDescription)
        separator(CCFlags.growX, CCFlags.pushX)
        gearButton(DuplicateRuntimeAction(runtime), RemoveRuntimeAction(target, runtime))
      }
      row {
        val languageUI = runtime.getRuntimeType().createConfigurable(project, runtime, config)
          .also { runtimeConfigurables.add(it) }
          .let {
            it.createComponent() ?: throw IllegalStateException("for runtime: $runtime")
          }
        languageUI(CCFlags.growX)
      }
    }
  }

  private fun createAddRuntimeHyperlink(): JButton {
    class Item(val type: LanguageRuntimeType<*>?) {
      override fun toString(): String {
        return type?.displayName ?: "Add language runtime"
      }
    }

    return DropDownLink(Item(null), LanguageRuntimeType.EXTENSION_NAME.extensionList.map { Item(it) }, Consumer {
      val newRuntime = it.type?.createDefaultConfig() ?: return@Consumer
      config.runtimes.addConfig(newRuntime)
      forceRefreshUI()
    })
  }

  private fun allConfigurables() = sequenceOf(targetConfigurable) + runtimeConfigurables.asSequence()

  override fun resetOptionsPanel() {
    runtimeConfigurables.clear()
    super.resetOptionsPanel()
  }

  private fun forceRefreshUI() {
    resetOptionsPanel()
    createComponent()?.revalidate()
  }

  private abstract inner class ChangeRuntimeActionBase(protected val runtime: LanguageRuntimeConfiguration,
                                                       @NlsActions.ActionText text: String) : AnAction(text)

  private inner class DuplicateRuntimeAction(runtime: LanguageRuntimeConfiguration)
    : ChangeRuntimeActionBase(runtime, ExecutionBundle.message("targets.details.action.duplicate.text")) {
    override fun actionPerformed(e: AnActionEvent) {
      val copy = runtime.getRuntimeType().duplicateConfig(runtime)
      config.runtimes.addConfig(copy)
      forceRefreshUI()
    }
  }

  private inner class RemoveRuntimeAction(private val target: TargetEnvironmentConfiguration, runtime: LanguageRuntimeConfiguration)
    : ChangeRuntimeActionBase(runtime, ExecutionBundle.message("targets.details.action.remove.text")) {
    override fun actionPerformed(e: AnActionEvent) {
      config.runtimes.removeConfig(runtime)
      forceRefreshUI()
    }

    override fun update(e: AnActionEvent) {
      val lastLanguage = target.runtimes.resolvedConfigs().filter { it != runtime }.isEmpty()
      e.presentation.isEnabled = !lastLanguage
    }
  }
}