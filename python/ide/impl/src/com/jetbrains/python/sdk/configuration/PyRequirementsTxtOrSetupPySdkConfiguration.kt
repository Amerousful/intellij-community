// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.sdk.configuration

import com.intellij.CommonBundle
import com.intellij.codeInspection.util.IntentionName
import com.intellij.execution.ExecutionException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.use
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.jetbrains.python.PyBundle
import com.jetbrains.python.PyCharmCommunityCustomizationBundle
import com.jetbrains.python.PySdkBundle
import com.jetbrains.python.packaging.PyPackageManagerImpl
import com.jetbrains.python.packaging.PyPackageUtil
import com.jetbrains.python.sdk.*
import com.jetbrains.python.sdk.add.PyAddNewVirtualEnvFromFilePanel
import com.jetbrains.python.sdk.configuration.PySdkConfigurationCollector.Companion.InputData
import com.jetbrains.python.sdk.configuration.PySdkConfigurationCollector.Companion.Source
import com.jetbrains.python.sdk.configuration.PySdkConfigurationCollector.Companion.VirtualEnvResult
import java.awt.BorderLayout
import java.awt.Insets
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JPanel

class PyRequirementsTxtOrSetupPySdkConfiguration : PyProjectSdkConfigurationExtension {

  private val LOGGER = Logger.getInstance(PyRequirementsTxtOrSetupPySdkConfiguration::class.java)

  override fun isApplicable(module: Module): Boolean = getRequirementsTxtOrSetupPy(module) != null

  override fun createAndAddSdkForConfigurator(module: Module) = createAndAddSdk(module, Source.CONFIGURATOR)

  override fun getIntentionName(module: Module): @IntentionName String {
    return PyCharmCommunityCustomizationBundle.message("sdk.create.venv.suggestion", getRequirementsTxtOrSetupPy(module)?.name)
  }

  override fun createAndAddSdkForInspection(module: Module) = createAndAddSdk(module, Source.INSPECTION)

  private fun createAndAddSdk(module: Module, source: Source): Sdk? {
    val existingSdks = ProjectJdkTable.getInstance().allJdks.asList()

    val (location, chosenBaseSdk, requirementsTxtOrSetupPy) = askForEnvData(module, existingSdks, source) ?: return null
    val baseSdk = installSdkIfNeeded(chosenBaseSdk!!, module, existingSdks) ?: return null
    val systemIndependentLocation = FileUtil.toSystemIndependentName(location)

    Disposer.newDisposable("Creating virtual environment").use {
      PyTemporarilyIgnoredFileProvider.ignoreRoot(systemIndependentLocation, it)

      return createVirtualEnv(module, baseSdk, location, requirementsTxtOrSetupPy, existingSdks)?.also {
        PySdkSettings.instance.onVirtualEnvCreated(
          baseSdk,
          systemIndependentLocation,
          module.basePath ?: module.project.basePath
        )
      }
    }
  }

  private fun getRequirementsTxtOrSetupPy(module: Module) =
    PyPackageUtil.findRequirementsTxt(module) ?: PyPackageUtil.findSetupPy(module)?.virtualFile

  private fun askForEnvData(module: Module, existingSdks: List<Sdk>, source: Source): PyAddNewVirtualEnvFromFilePanel.Data? {
    val requirementsTxtOrSetupPy = getRequirementsTxtOrSetupPy(module) ?: return null

    var permitted = false
    var envData: PyAddNewVirtualEnvFromFilePanel.Data? = null

    ApplicationManager.getApplication().invokeAndWait {
      val dialog = Dialog(module, existingSdks, requirementsTxtOrSetupPy)

      permitted = dialog.showAndGet()
      envData = dialog.envData

      LOGGER.debug("Dialog exit code: ${dialog.exitCode}, $permitted")
    }

    PySdkConfigurationCollector.logVirtualEnvDialog(
      module.project,
      permitted,
      source,
      if (envData?.baseSdk == null) InputData.NOT_FILLED else InputData.SPECIFIED
    )

    return if (permitted) envData else null
  }

  private fun createVirtualEnv(module: Module,
                               baseSdk: Sdk,
                               location: String,
                               requirementsTxtOrSetupPy: String,
                               existingSdks: List<Sdk>): Sdk? {
    ProgressManager.progress(PySdkBundle.message("python.creating.venv.sentence"))
    LOGGER.debug("Creating virtual environment")

    val path = try {
      PyPackageManagerImpl.getInstance(baseSdk).createVirtualEnv(location, false)
    }
    catch (e: ExecutionException) {
      PySdkConfigurationCollector.logVirtualEnv(module.project, VirtualEnvResult.CREATION_FAILURE)
      LOGGER.warn("Exception during creating virtual environment", e)
      showSdkExecutionException(baseSdk, e, PySdkBundle.message("python.creating.venv.failed.title"))
      return null
    }

    val basePath = module.basePath

    LOGGER.debug("Setting up associated virtual environment: $path, $basePath")
    val sdk = PyDetectedSdk(path).setupAssociated(existingSdks, basePath) ?: return null

    ApplicationManager.getApplication().invokeAndWait {
      LOGGER.debug("Adding associated virtual environment: $path, $basePath")
      SdkConfigurationUtil.addSdk(sdk)
      sdk.associateWithModule(module, null)
    }

    val requirementsTxtOrSetupPyFile = VfsUtil.findFile(Paths.get(requirementsTxtOrSetupPy), false)
    if (requirementsTxtOrSetupPyFile == null) {
      PySdkConfigurationCollector.logVirtualEnv(module.project, VirtualEnvResult.DEPS_NOT_FOUND)
      LOGGER.warn("File with dependencies is not found: $requirementsTxtOrSetupPy")
    }
    else {
      ProgressManager.progress(PyBundle.message("python.packaging.installing.packages"))
      LOGGER.debug("Installing packages")

      try {
        PyPackageManagerImpl.getInstance(sdk).install(emptyList(), getCommandForPipInstall(requirementsTxtOrSetupPyFile))
      }
      catch (e: ExecutionException) {
        PySdkConfigurationCollector.logVirtualEnv(module.project, VirtualEnvResult.INSTALLATION_FAILURE)
        LOGGER.warn("Exception during installing packages", e)
        showSdkExecutionException(sdk, e, PyBundle.message("python.packaging.failed.to.install.packages.title"))
      }
    }

    PySdkConfigurationCollector.logVirtualEnv(module.project, VirtualEnvResult.CREATED)
    return sdk
  }

  private fun getCommandForPipInstall(requirementsTxtOrSetupPy: VirtualFile): List<String> {
    return if (requirementsTxtOrSetupPy.fileType == PlainTextFileType.INSTANCE) {
      listOf("-r", getAbsPath(requirementsTxtOrSetupPy))
    }
    else {
      listOf("-e", getAbsPath(requirementsTxtOrSetupPy.parent))
    }
  }

  @NlsSafe
  private fun getAbsPath(file: VirtualFile): String = file.toNioPath().toAbsolutePath().toString()

  private class Dialog(module: Module,
                       existingSdks: List<Sdk>,
                       private val requirementsTxtOrSetupPy: VirtualFile) : DialogWrapper(module.project, false, IdeModalityType.PROJECT) {

    private val panel = PyAddNewVirtualEnvFromFilePanel(module, existingSdks, requirementsTxtOrSetupPy)

    val envData
      get() = panel.envData

    init {
      title = PySdkBundle.message("python.creating.venv.title")
      init()
      Disposer.register(disposable) { if (isOK) panel.logData() }
    }

    override fun createCenterPanel(): JComponent {
      return JPanel(BorderLayout()).apply {
        val border = IdeBorderFactory.createEmptyBorder(Insets(4, 0, 6, 0))
        val message = PyCharmCommunityCustomizationBundle.message("sdk.create.venv.permission", requirementsTxtOrSetupPy.name)

        add(
          JBUI.Panels.simplePanel(JBLabel(message)).withBorder(border),
          BorderLayout.NORTH
        )

        add(panel, BorderLayout.CENTER)
      }
    }

    override fun postponeValidation(): Boolean {
      return false
    }

    override fun doValidateAll(): List<ValidationInfo> = panel.validateAll(CommonBundle.getOkButtonText())
  }
}