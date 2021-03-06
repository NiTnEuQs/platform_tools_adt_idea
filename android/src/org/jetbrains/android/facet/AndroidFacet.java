/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.android.facet;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.builder.model.ArtifactInfo;
import com.android.builder.model.SourceProvider;
import com.android.builder.model.Variant;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.prefs.AndroidLocation;
import com.android.sdklib.AndroidVersion;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.SdkManager;
import com.android.sdklib.internal.avd.AvdInfo;
import com.android.sdklib.internal.avd.AvdManager;
import com.android.tools.idea.configurations.ConfigurationManager;
import com.android.tools.idea.gradle.IdeaAndroidProject;
import com.android.tools.idea.rendering.ProjectResources;
import com.android.utils.ILogger;
import com.google.common.collect.Lists;
import com.intellij.CommonBundle;
import com.intellij.ProjectTopics;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.facet.Facet;
import com.intellij.facet.FacetManager;
import com.intellij.facet.FacetTypeId;
import com.intellij.facet.FacetTypeRegistry;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkAdditionalData;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Processor;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.HashSet;
import com.intellij.util.xml.ConvertContext;
import com.intellij.util.xml.DomElement;
import org.jetbrains.android.compiler.AndroidAptCompiler;
import org.jetbrains.android.compiler.AndroidAutogeneratorMode;
import org.jetbrains.android.compiler.AndroidCompileUtil;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.resourceManagers.LocalResourceManager;
import org.jetbrains.android.resourceManagers.ResourceManager;
import org.jetbrains.android.resourceManagers.SystemResourceManager;
import org.jetbrains.android.sdk.*;
import org.jetbrains.android.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.android.model.impl.JpsAndroidModuleProperties;

import java.io.File;
import java.util.*;

import static org.jetbrains.android.util.AndroidUtils.SYSTEM_RESOURCE_PACKAGE;

/**
 * @author yole
 */
public final class AndroidFacet extends Facet<AndroidFacetConfiguration> {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.android.facet.AndroidFacet");

  public static final FacetTypeId<AndroidFacet> ID = new FacetTypeId<AndroidFacet>("android");
  public static final String NAME = "Android";

  private AvdManager myAvdManager = null;
  private SdkManager mySdkManager;

  private SystemResourceManager mySystemResourceManager;
  private LocalResourceManager myLocalResourceManager;

  private final Map<String, Map<String, SmartPsiElementPointer<PsiClass>>> myClassMaps =
    new HashMap<String, Map<String, SmartPsiElementPointer<PsiClass>>>();

  private final Object myClassMapLock = new Object();

  private final Set<AndroidAutogeneratorMode> myDirtyModes = EnumSet.noneOf(AndroidAutogeneratorMode.class);
  private final Map<AndroidAutogeneratorMode, Set<String>> myAutogeneratedFiles = new HashMap<AndroidAutogeneratorMode, Set<String>>();

  private volatile boolean myAutogenerationEnabled = false;

  private ConfigurationManager myConfigurationManager;
  private ProjectResources myProjectResources;
  private ProjectResources myProjectResourcesWithLibraries;
  private IdeaAndroidProject myIdeaAndroidProject;

  private final List<GradleProjectAvailableListener> myGradleProjectAvailableListeners = Lists.newArrayList();
  private SourceProvider myMainSourceSet;
  private IdeaSourceProvider myMainIdeaSourceSet;

  public AndroidFacet(@NotNull Module module, String name, @NotNull AndroidFacetConfiguration configuration) {
    super(getFacetType(), module, name, configuration, null);
    configuration.setFacet(this);
  }

  public boolean isAutogenerationEnabled() {
    return myAutogenerationEnabled;
  }

  public boolean isGradleProject() {
    return !getConfiguration().getState().ALLOW_USER_CONFIGURATION;
  }

  public boolean isLibraryProject() {
    return getConfiguration().getState().LIBRARY_PROJECT;
  }

  public void setLibraryProject(boolean library) {
    getConfiguration().getState().LIBRARY_PROJECT = library;
  }

  /**
   * Returns the main source set of the project. For non-Gradle projects it returns a {@link SourceProvider} wrapper
   * which provides information about the old project.
   *
   * @return the main source set
   */
  @NotNull
  public SourceProvider getMainSourceSet() {
    if (myIdeaAndroidProject != null) {
      return myIdeaAndroidProject.getDelegate().getDefaultConfig().getSourceProvider();
    } else {
      return new LegacySourceProvider();
    }
  }

  @NotNull
  public IdeaSourceProvider getMainIdeaSourceSet() {
    if (!isGradleProject()) {
      if (myMainIdeaSourceSet == null) {
        myMainIdeaSourceSet = IdeaSourceProvider.create(this);
      }
    } else {
      SourceProvider mainSourceSet = getMainSourceSet();
      if (mainSourceSet != myMainSourceSet) {
        myMainIdeaSourceSet = IdeaSourceProvider.create(mainSourceSet);
      }
    }

    return myMainIdeaSourceSet;
  }

  /**
   * Returns the source provider for the current build type, which will never be null for a Gradle based
   * Android project, and always null for a legacy Android project
   *
   * @return the build type source set or null
   */
  @Nullable
  public SourceProvider getBuildTypeSourceSet() {
    if (myIdeaAndroidProject != null) {
      Variant selectedVariant = myIdeaAndroidProject.getSelectedVariant();
      return myIdeaAndroidProject.getDelegate().getBuildTypes().get(selectedVariant.getBuildType()).getSourceProvider();
    } else {
      return null;
    }
  }

  /**
   * Like {@link #getBuildTypeSourceSet()} but typed for internal IntelliJ usage with
   * {@link VirtualFile} instead of {@link File} references
   *
   * @return the build type source set or null
   */
  @Nullable
  public IdeaSourceProvider getIdeaBuildTypeSourceSet() {
    SourceProvider sourceProvider = getBuildTypeSourceSet();
    if (sourceProvider != null) {
      return IdeaSourceProvider.create(sourceProvider);
    } else {
      return null;
    }
  }

  /**
   * Returns all resource directories, in the overlay order
   * <p>
   * TODO: This should be changed to be a {@code List<List<VirtualFile>>} in order to be
   * able to distinguish overlays (e.g. flavor directories) versus resource folders at
   * the same level where duplicates are NOT allowed: [[flavor1], [flavor2], [main1,main2]]
   *
   * @return a list of all resource directories
   */
  @NotNull
  public List<VirtualFile> getAllResourceDirectories() {
    if (isGradleProject()) {
      List<VirtualFile> resDirectories = new ArrayList<VirtualFile>();
      resDirectories.addAll(getMainIdeaSourceSet().getResDirectories());
      List<IdeaSourceProvider> flavorSourceSets = getIdeaFlavorSourceSets();
      if (flavorSourceSets != null) {
        for (IdeaSourceProvider provider : flavorSourceSets) {
          resDirectories.addAll(provider.getResDirectories());
        }
      }

      IdeaSourceProvider buildTypeSourceSet = getIdeaBuildTypeSourceSet();
      if (buildTypeSourceSet != null) {
        resDirectories.addAll(buildTypeSourceSet.getResDirectories());
      }

      return resDirectories;
    } else {
      return new ArrayList<VirtualFile>(getMainIdeaSourceSet().getResDirectories());
    }
  }

  /**
   * Returns the name of the build type
   */
  @Nullable
  public String getBuildTypeName() {
    return myIdeaAndroidProject != null ? myIdeaAndroidProject.getSelectedVariant().getName() : null;
  }

  /**
   * Returns the source providers for the available flavors, which will never be null for a Gradle based
   * Android project, and always null for a legacy Android project
   *
   * @return the flavor source providers or null in legacy projects
   */
  @Nullable
  public List<SourceProvider> getFlavorSourceSets() {
    if (myIdeaAndroidProject != null) {
      Variant selectedVariant = myIdeaAndroidProject.getSelectedVariant();
      List<String> productFlavors = selectedVariant.getProductFlavors();
      List<SourceProvider> providers = Lists.newArrayList();
      for (String flavor : productFlavors) {
        providers.add(myIdeaAndroidProject.getDelegate().getProductFlavors().get(flavor).getSourceProvider());
      }

      return providers;
    } else {
      return null;
    }
  }

  /**
   * Like {@link #getFlavorSourceSets()} but typed for internal IntelliJ usage with
   * {@link VirtualFile} instead of {@link File} references
   *
   * @return the flavor source providers or null in legacy projects
   */
  @Nullable
  public List<IdeaSourceProvider> getIdeaFlavorSourceSets() {
    List<SourceProvider> sourceProviders = getFlavorSourceSets();
    if (sourceProviders != null) {
      List<IdeaSourceProvider> ideaSourceProviders = Lists.newArrayListWithExpectedSize(sourceProviders.size());
      for (SourceProvider provider : sourceProviders) {
        ideaSourceProviders.add(IdeaSourceProvider.create(provider));
      }

      return ideaSourceProviders;
    } else {
      return null;
    }
  }

  /**
   * Like {@link #getFlavorSourceSets()}, but returns the name for each flavor's source provider as well
   *
   * @return a list of source provider and name pairs or null in legacy projects
   */
  @Nullable
  public List<Pair<String, SourceProvider>> getFlavorSourceSetsAndNames() {
    if (myIdeaAndroidProject != null) {
      Variant selectedVariant = myIdeaAndroidProject.getSelectedVariant();
      List<String> productFlavors = selectedVariant.getProductFlavors();
      List<Pair<String, SourceProvider>> providers = Lists.newArrayList();
      for (String flavor : productFlavors) {
        providers.add(Pair.create(flavor, myIdeaAndroidProject.getDelegate().getProductFlavors().get(flavor).getSourceProvider()));
      }

      return providers;
    } else {
      return null;
    }
  }

  /**
   * Like {@link #getFlavorSourceSetsAndNames()} but typed for internal IntelliJ usage with
   * {@link VirtualFile} instead of {@link File} references
   *
   * @return a list of source provider and name pairs or null in legacy projects
   */
  @Nullable
  public List<Pair<String, IdeaSourceProvider>> getIdeaFlavorSourceSetsAndNames() {
    List<Pair<String, SourceProvider>> pairs = getFlavorSourceSetsAndNames();
    if (pairs != null) {
      List<Pair<String, IdeaSourceProvider>> providers = Lists.newArrayListWithExpectedSize(pairs.size());
      for (Pair<String, SourceProvider> pair : pairs) {
        String flavor = pair.getFirst();
        SourceProvider provider = pair.getSecond();
        providers.add(Pair.create(flavor, IdeaSourceProvider.create(provider)));
      }

      return providers;
    } else {
      return null;
    }
  }


  /**
   * This returns the primary resource directory; the default location to place
   * newly created resources etc.  This method is marked deprecated since we should
   * be gradually adding in UI to allow users to choose specific resource folders
   * among the available flavors (see {@link #getFlavorSourceSets()} etc).
   *
   * @return the primary resource dir, if any
   */
  @Deprecated
  @Nullable
  public VirtualFile getPrimaryResourceDir() {
    Set<VirtualFile> resDirectories = getMainIdeaSourceSet().getResDirectories();
    if (!resDirectories.isEmpty()) {
      return resDirectories.iterator().next();
    }

    return null;
  }

  public boolean isGeneratedFileRemoved(@NotNull AndroidAutogeneratorMode mode) {
    synchronized (myAutogeneratedFiles) {
      final Set<String> filePaths = myAutogeneratedFiles.get(mode);

      if (filePaths != null) {
        for (String path : filePaths) {
          final VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);

          if (file == null) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public void clearAutogeneratedFiles(@NotNull AndroidAutogeneratorMode mode) {
    synchronized (myAutogeneratedFiles) {
      final Set<String> set = myAutogeneratedFiles.get(mode);
      if (set != null) {
        set.clear();
      }
    }
  }

  public void markFileAutogenerated(@NotNull AndroidAutogeneratorMode mode, @NotNull VirtualFile file) {
    synchronized (myAutogeneratedFiles) {
      Set<String> set = myAutogeneratedFiles.get(mode);

      if (set == null) {
        set = new HashSet<String>();
        myAutogeneratedFiles.put(mode, set);
      }
      set.add(file.getPath());
    }
  }

  @NotNull
  public Set<String> getAutogeneratedFiles(@NotNull AndroidAutogeneratorMode mode) {
    synchronized (myAutogeneratedFiles) {
      final Set<String> set = myAutogeneratedFiles.get(mode);
      return set != null ? new HashSet<String>(set) : Collections.<String>emptySet();
    }
  }

  private void activateSourceAutogenerating() {
    myAutogenerationEnabled = true;
  }

  public void androidPlatformChanged() {
    myAvdManager = null;
    myLocalResourceManager = null;
    mySystemResourceManager = null;
    myClassMaps.clear();
  }

  // can be invoked only from dispatch thread!
  @Nullable
  public AndroidDebugBridge getDebugBridge() {
    AndroidPlatform platform = getConfiguration().getAndroidPlatform();
    if (platform != null) {
      return platform.getSdkData().getDebugBridge(getModule().getProject());
    }
    return null;
  }

  public AvdInfo[] getAllAvds() {
    AvdManager manager = getAvdManagerSilently();
    if (manager != null) {
      if (reloadAvds(manager)) {
        return manager.getAllAvds();
      }
    }
    return new AvdInfo[0];
  }

  private boolean reloadAvds(AvdManager manager) {
    try {
      MessageBuildingSdkLog log = new MessageBuildingSdkLog();
      manager.reloadAvds(log);
      if (log.getErrorMessage().length() > 0) {
        Messages
          .showErrorDialog(getModule().getProject(), AndroidBundle.message("cant.load.avds.error.prefix") + ' ' + log.getErrorMessage(),
                           CommonBundle.getErrorTitle());
      }
      return true;
    }
    catch (AndroidLocation.AndroidLocationException e) {
      Messages.showErrorDialog(getModule().getProject(), AndroidBundle.message("cant.load.avds.error"), CommonBundle.getErrorTitle());
    }
    return false;
  }

  public AvdInfo[] getAllCompatibleAvds() {
    List<AvdInfo> result = new ArrayList<AvdInfo>();
    addCompatibleAvds(result, getAllAvds());
    return result.toArray(new AvdInfo[result.size()]);
  }

  public AvdInfo[] getValidCompatibleAvds() {
    AvdManager manager = getAvdManagerSilently();
    List<AvdInfo> result = new ArrayList<AvdInfo>();
    if (manager != null && reloadAvds(manager)) {
      addCompatibleAvds(result, manager.getValidAvds());
    }
    return result.toArray(new AvdInfo[result.size()]);
  }

  private AvdInfo[] addCompatibleAvds(List<AvdInfo> to, @NotNull AvdInfo[] from) {
    for (AvdInfo avd : from) {
      if (isCompatibleAvd(avd)) {
        to.add(avd);
      }
    }
    return to.toArray(new AvdInfo[to.size()]);
  }

  @Nullable
  private static AndroidVersion getDeviceVersion(IDevice device) {
    try {
      Map<String, String> props = device.getProperties();
      String apiLevel = props.get(IDevice.PROP_BUILD_API_LEVEL);
      if (apiLevel == null) {
        return null;
      }

      return new AndroidVersion(Integer.parseInt(apiLevel), props.get((IDevice.PROP_BUILD_CODENAME)));
    }
    catch (NumberFormatException e) {
      return null;
    }
  }

  @Nullable
  public Boolean isCompatibleDevice(@NotNull IDevice device) {
    String avd = device.getAvdName();
    IAndroidTarget target = getConfiguration().getAndroidTarget();
    if (target == null) return false;
    if (avd != null) {
      AvdManager avdManager = getAvdManagerSilently();
      if (avdManager == null) return true;
      AvdInfo info = avdManager.getAvd(avd, true);
      return isCompatibleBaseTarget(info != null ? info.getTarget() : null);
    }
    if (target.isPlatform()) {
      AndroidVersion deviceVersion = getDeviceVersion(device);
      if (deviceVersion != null) {
        return canRunOnDevice(target, deviceVersion);
      }
    }
    return null;
  }

  // if baseTarget is null, then function return if application can be deployed on any target

  public boolean isCompatibleBaseTarget(@Nullable IAndroidTarget baseTarget) {
    IAndroidTarget target = getConfiguration().getAndroidTarget();
    if (target == null) return false;
    AndroidVersion baseTargetVersion = baseTarget != null ? baseTarget.getVersion() : null;
    if (!canRunOnDevice(target, baseTargetVersion)) return false;
    if (!target.isPlatform()) {
      if (baseTarget == null) return false;
      // then it is add-on
      if (!Comparing.equal(target.getVendor(), baseTarget.getVendor()) || !Comparing.equal(target.getName(), baseTarget.getName())) {
        return false;
      }
    }
    return true;
  }

  private boolean canRunOnDevice(IAndroidTarget projectTarget, AndroidVersion deviceVersion) {
    int minSdkVersion = -1;
    int maxSdkVersion = -1;
    final Manifest manifest = getManifest();
    if (manifest != null) {
      XmlTag manifestTag = ApplicationManager.getApplication().runReadAction(new Computable<XmlTag>() {
        @Override
        public XmlTag compute() {
          return manifest.getXmlTag();
        }
      });
      if (manifestTag != null) {
        XmlTag[] tags = manifestTag.findSubTags("uses-sdk");
        for (XmlTag tag : tags) {
          int candidate = AndroidUtils.getIntAttrValue(tag, "minSdkVersion");
          if (candidate >= 0) minSdkVersion = candidate;
          candidate = AndroidUtils.getIntAttrValue(tag, "maxSdkVersion");
          if (candidate >= 0) maxSdkVersion = candidate;
        }
      }
    }

    int baseApiLevel = deviceVersion != null ? deviceVersion.getApiLevel() : 1;
    AndroidVersion targetVersion = projectTarget.getVersion();
    if (minSdkVersion < 0) minSdkVersion = targetVersion.getApiLevel();
    if (minSdkVersion > baseApiLevel) return false;
    if (maxSdkVersion >= 0 && maxSdkVersion < baseApiLevel) return false;
    String codeName = targetVersion.getCodename();
    String baseCodeName = deviceVersion != null ? deviceVersion.getCodename() : null;
    if (codeName != null && !codeName.equals(baseCodeName)) {
      return false;
    }
    return true;
  }

  public boolean isCompatibleAvd(@NotNull AvdInfo avd) {
    IAndroidTarget target = getConfiguration().getAndroidTarget();
    return target != null && avd.getTarget() != null && isCompatibleBaseTarget(avd.getTarget());
  }

  @Nullable
  public AvdManager getAvdManagerSilently() {
    try {
      return getAvdManager(new AvdManagerLog());
    }
    catch (AvdsNotSupportedException ignored) {
    }
    catch (AndroidLocation.AndroidLocationException ignored) {
    }
    return null;
  }

  @NotNull
  public AvdManager getAvdManager(ILogger log) throws AvdsNotSupportedException, AndroidLocation.AndroidLocationException {
    if (myAvdManager == null) {
      SdkManager sdkManager = getSdkManager();
      if (sdkManager != null) {
        myAvdManager = AvdManager.getInstance(sdkManager, log);
      }
      else {
        throw new AvdsNotSupportedException();
      }
    }
    return myAvdManager;
  }

  @Nullable
  public SdkManager getSdkManager() {
    if (mySdkManager == null) {
      AndroidPlatform platform = getConfiguration().getAndroidPlatform();
      AndroidSdkData sdkData = platform != null ? platform.getSdkData() : null;

      if (sdkData != null) {
        mySdkManager = sdkData.getSdkManager();
      }
    }

    return mySdkManager;
  }

  /**
   * Returns a target from a hash that was generated by {@link IAndroidTarget#hashString()}.
   *
   * @param hash the {@link IAndroidTarget} hash string.
   * @return The matching {@link IAndroidTarget} or null.
   */
  @Nullable
  public IAndroidTarget getTargetFromHashString(@NotNull String hash) {
    SdkManager sdkManager = getSdkManager();
    return sdkManager != null ? sdkManager.getTargetFromHashString(hash) : null;
  }

  public void launchEmulator(@Nullable final String avdName, @NotNull final String commands, @NotNull final ProcessHandler handler) {
    AndroidPlatform platform = getConfiguration().getAndroidPlatform();
    if (platform != null) {
      final String emulatorPath = platform.getSdkData().getLocation() + File.separator + AndroidCommonUtils
        .toolPath(SdkConstants.FN_EMULATOR);
      final GeneralCommandLine commandLine = new GeneralCommandLine();
      commandLine.setExePath(FileUtil.toSystemDependentName(emulatorPath));
      if (avdName != null) {
        commandLine.addParameter("-avd");
        commandLine.addParameter(avdName);
      }
      String[] params = ParametersList.parse(commands);
      for (String s : params) {
        if (s.length() > 0) {
          commandLine.addParameter(s);
        }
      }
      handler.notifyTextAvailable(commandLine.getCommandLineString() + '\n', ProcessOutputTypes.STDOUT);

      ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
        @Override
        public void run() {
          try {
            AndroidUtils.executeCommand(commandLine, new OutputProcessor() {
              @Override
              public void onTextAvailable(@NotNull String text) {
                handler.notifyTextAvailable(text, ProcessOutputTypes.STDOUT);
              }
            }, WaitingStrategies.WaitForTime.getInstance(5000));
          }
          catch (ExecutionException e) {
            final String stackTrace = AndroidCommonUtils.getStackTrace(e);
            handler.notifyTextAvailable(stackTrace, ProcessOutputTypes.STDERR);
          }
        }
      });
    }
  }

  @Override
  public void initFacet() {
    StartupManager.getInstance(getModule().getProject()).runWhenProjectIsInitialized(new Runnable() {
        @Override
        public void run() {
          AndroidResourceFilesListener.notifyFacetInitialized(AndroidFacet.this);
          if (ApplicationManager.getApplication().isUnitTestMode()) {
            return;
          }

          addResourceFolderToSdkRootsIfNecessary();

          ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
              Module module = getModule();
              Project project = module.getProject();
              if (project.isDisposed()) {
                return;
              }

              if (AndroidAptCompiler.isToCompileModule(module, getConfiguration())) {
                AndroidCompileUtil.generate(module, AndroidAutogeneratorMode.AAPT);
              }
              AndroidCompileUtil.generate(module, AndroidAutogeneratorMode.AIDL);
              AndroidCompileUtil.generate(module, AndroidAutogeneratorMode.RENDERSCRIPT);
              AndroidCompileUtil.generate(module, AndroidAutogeneratorMode.BUILDCONFIG);

              activateSourceAutogenerating();
            }
          });
        }
      });

    getModule().getMessageBus().connect(this).subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootAdapter() {
      private Sdk myPrevSdk;

      @Override
      public void rootsChanged(final ModuleRootEvent event) {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
          @Override
          public void run() {
            if (isDisposed()) {
              return;
            }
            final ModuleRootManager rootManager = ModuleRootManager.getInstance(getModule());

            final Sdk newSdk = rootManager.getSdk();
            if (newSdk != null && newSdk.getSdkType() instanceof AndroidSdkType && !newSdk.equals(myPrevSdk)) {
              androidPlatformChanged();

              synchronized (myDirtyModes) {
                myDirtyModes.addAll(Arrays.asList(AndroidAutogeneratorMode.values()));
              }
            }
            myPrevSdk = newSdk;
          }
        });
      }
    });
  }
  
  private void addResourceFolderToSdkRootsIfNecessary() {
    final Sdk sdk = ModuleRootManager.getInstance(getModule()).getSdk();
    if (sdk == null || !(sdk.getSdkType() instanceof AndroidSdkType)) {
      return;
    }

    final SdkAdditionalData data = sdk.getSdkAdditionalData();
    if (!(data instanceof AndroidSdkAdditionalData)) {
      return;
    }

    final AndroidPlatform platform = ((AndroidSdkAdditionalData)data).getAndroidPlatform();
    if (platform == null) {
      return;
    }

    final String resFolderPath = platform.getTarget().getPath(IAndroidTarget.RESOURCES);
    if (resFolderPath == null) {
      return;
    }
    final List<VirtualFile> filesToAdd = new ArrayList<VirtualFile>();

    final VirtualFile resFolder = LocalFileSystem.getInstance().findFileByPath(resFolderPath);
    if (resFolder != null) {
      filesToAdd.add(resFolder);
    }

    if (platform.needToAddAnnotationsJarToClasspath()) {
      final String sdkHomePath = FileUtil.toSystemIndependentName(platform.getSdkData().getLocation());
      final VirtualFile annotationsJar = JarFileSystem.getInstance().findFileByPath(
        sdkHomePath + AndroidCommonUtils.ANNOTATIONS_JAR_RELATIVE_PATH + JarFileSystem.JAR_SEPARATOR);
      if (annotationsJar != null) {
        filesToAdd.add(annotationsJar);
      }
    }

    addFilesToSdkIfNecessary(sdk, filesToAdd);
  }

  private static void addFilesToSdkIfNecessary(@NotNull Sdk sdk, @NotNull Collection<VirtualFile> files) {
    final List<VirtualFile> newFiles = new ArrayList<VirtualFile>(files);
    newFiles.removeAll(Arrays.asList(sdk.getRootProvider().getFiles(OrderRootType.CLASSES)));

    if (newFiles.size() > 0) {
      final SdkModificator modificator = sdk.getSdkModificator();

      for (VirtualFile file : newFiles) {
        modificator.addRoot(file, OrderRootType.CLASSES);
      }
      modificator.commitChanges();
    }
  }

  @Override
  public void disposeFacet() {
    if (myConfigurationManager != null) {
      myConfigurationManager.dispose();
    }
  }

  @Nullable
  public static AndroidFacet getInstance(@NotNull Module module) {
    return FacetManager.getInstance(module).getFacetByType(ID);
  }

  @Nullable
  public static AndroidFacet getInstance(@NotNull ConvertContext context) {
    Module module = context.getModule();
    return module != null ? getInstance(module) : null;
  }

  @Nullable
  public static AndroidFacet getInstance(@NotNull final PsiElement element) {
    Module module = ApplicationManager.getApplication().runReadAction(new Computable<Module>() {
      @Nullable
      @Override
      public Module compute() {
        return ModuleUtil.findModuleForPsiElement(element);
      }
    });
    if (module == null) return null;
    return getInstance(module);
  }

  @Nullable
  public static AndroidFacet getInstance(@NotNull DomElement element) {
    Module module = element.getModule();
    if (module == null) return null;
    return getInstance(module);
  }

  @Nullable
  public ResourceManager getResourceManager(@Nullable String resourcePackage) {
    return getResourceManager(resourcePackage, null);
  }

  public ResourceManager getResourceManager(@Nullable String resourcePackage, @Nullable PsiElement contextElement) {
    if (SYSTEM_RESOURCE_PACKAGE.equals(resourcePackage)) {
      return getSystemResourceManager();
    }
    if (contextElement != null && isInAndroidSdk(contextElement)) {
      return getSystemResourceManager();
    }
    return getLocalResourceManager();
  }

  private static boolean isInAndroidSdk(@NonNull PsiElement element) {
    final PsiFile file = element.getContainingFile();

    if (file == null) {
      return false;
    }
    final VirtualFile vFile = file.getVirtualFile();

    if (vFile == null) {
      return false;
    }
    final ProjectFileIndex projectFileIndex = ProjectRootManager.getInstance(element.getProject()).getFileIndex();
    final List<OrderEntry> entries = projectFileIndex.getOrderEntriesForFile(vFile);

    for (OrderEntry entry : entries) {
      if (entry instanceof JdkOrderEntry) {
        final Sdk sdk = ((JdkOrderEntry)entry).getJdk();

        if (sdk != null && sdk.getSdkType() instanceof AndroidSdkType) {
          return true;
        }
      }
    }
    return false;
  }

  @NotNull
  public LocalResourceManager getLocalResourceManager() {
    if (myLocalResourceManager == null) {
      myLocalResourceManager = new LocalResourceManager(this);
    }
    return myLocalResourceManager;
  }

  @Nullable
  public SystemResourceManager getSystemResourceManager() {
    if (mySystemResourceManager == null) {
      AndroidPlatform platform = getConfiguration().getAndroidPlatform();
      if (platform != null) {
        mySystemResourceManager = new SystemResourceManager(this, platform);
      }
    }
    return mySystemResourceManager;
  }

  @Nullable
  public Manifest getManifest() {
    File manifestIoFile = getMainSourceSet().getManifestFile();
    if (manifestIoFile == null) return null;

    final VirtualFile manifestFile =
      LocalFileSystem.getInstance().findFileByIoFile(manifestIoFile);
    if (manifestFile == null) return null;
    return AndroidUtils.loadDomElement(getModule(), manifestFile, Manifest.class);
  }

  public static AndroidFacetType getFacetType() {
    return (AndroidFacetType)FacetTypeRegistry.getInstance().findFacetType(ID);
  }

  // todo: correctly support classes from external non-platform jars
  @NotNull
  public Map<String, PsiClass> getClassMap(@NotNull String className, @NotNull ClassMapConstructor constructor) {
    synchronized (myClassMapLock) {
      Map<String, SmartPsiElementPointer<PsiClass>> classMap = getInitialClassMap(className, constructor, false);
      final Map<String, PsiClass> result = new HashMap<String, PsiClass>();
      boolean shouldRebuildInitialMap = false;

      for (final String key : classMap.keySet()) {
        final SmartPsiElementPointer<PsiClass> pointer = classMap.get(key);

        if (!isUpToDate(pointer, key, constructor)) {
          shouldRebuildInitialMap = true;
          break;
        }
        final PsiClass aClass = pointer.getElement();

        if (aClass != null) {
          result.put(key, aClass);
        }
      }

      if (shouldRebuildInitialMap) {
        result.clear();
        classMap = getInitialClassMap(className, constructor, true);

        for (final String key : classMap.keySet()) {
          final SmartPsiElementPointer<PsiClass> pointer = classMap.get(key);
          final PsiClass aClass = pointer.getElement();

          if (aClass != null) {
            result.put(key, aClass);
          }
        }
      }
      final Project project = getModule().getProject();
      fillMap(className, constructor, ProjectScope.getProjectScope(project), result, false);
      return result;
    }
  }

  private static boolean isUpToDate(SmartPsiElementPointer<PsiClass> pointer, String tagName, ClassMapConstructor constructor) {
    final PsiClass aClass = pointer.getElement();
    if (aClass == null) {
      return false;
    }
    final String[] tagNames = constructor.getTagNamesByClass(aClass);
    return ArrayUtil.find(tagNames, tagName) >= 0;
  }

  @NotNull
  private Map<String, SmartPsiElementPointer<PsiClass>> getInitialClassMap(@NotNull String className,
                                                                           @NotNull ClassMapConstructor constructor,
                                                                           boolean forceRebuild) {
    Map<String, SmartPsiElementPointer<PsiClass>> viewClassMap = myClassMaps.get(className);
    if (viewClassMap != null && !forceRebuild) return viewClassMap;
    final HashMap<String, PsiClass> map = new HashMap<String, PsiClass>();

    if (fillMap(className, constructor, getModule().getModuleWithDependenciesAndLibrariesScope(true), map, true)) {
      viewClassMap = new HashMap<String, SmartPsiElementPointer<PsiClass>>(map.size());
      final SmartPointerManager manager = SmartPointerManager.getInstance(getModule().getProject());

      for (Map.Entry<String, PsiClass> entry : map.entrySet()) {
        viewClassMap.put(entry.getKey(), manager.createSmartPsiElementPointer(entry.getValue()));
      }
      myClassMaps.put(className, viewClassMap);
    }
    return viewClassMap != null
           ? viewClassMap
           : Collections.<String, SmartPsiElementPointer<PsiClass>>emptyMap();
  }

  private boolean fillMap(@NotNull final String className,
                          @NotNull final ClassMapConstructor constructor,
                          GlobalSearchScope scope,
                          final Map<String, PsiClass> map,
                          final boolean libClassesOnly) {
    final JavaPsiFacade facade = JavaPsiFacade.getInstance(getModule().getProject());
    final PsiClass baseClass = ApplicationManager.getApplication().runReadAction(new Computable<PsiClass>() {
      @Override
      @Nullable
      public PsiClass compute() {
        return facade.findClass(className, getModule().getModuleWithDependenciesAndLibrariesScope(true));
      }
    });
    if (baseClass != null) {
      String[] baseClassTagNames = constructor.getTagNamesByClass(baseClass);
      for (String tagName : baseClassTagNames) {
        map.put(tagName, baseClass);
      }
      try {
        ClassInheritorsSearch.search(baseClass, scope, true).forEach(new Processor<PsiClass>() {
          @Override
          public boolean process(PsiClass c) {
            if (libClassesOnly && c.getManager().isInProject(c)) {
              return true;
            }
            String[] tagNames = constructor.getTagNamesByClass(c);
            for (String tagName : tagNames) {
              map.put(tagName, c);
            }
            return true;
          }
        });
      }
      catch (IndexNotReadyException e) {
        LOG.info(e);
        return false;
      }
    }
    return map.size() > 0;
  }


  public void scheduleSourceRegenerating(@NotNull final AndroidAutogeneratorMode mode) {
    synchronized (myDirtyModes) {
      myDirtyModes.add(mode);
    }
  }

  public boolean cleanRegeneratingState(@NotNull final AndroidAutogeneratorMode mode) {
    synchronized (myDirtyModes) {
      return myDirtyModes.remove(mode);
    }
  }

  @NotNull
  public ConfigurationManager getConfigurationManager() {
    if (myConfigurationManager == null) {
      myConfigurationManager = ConfigurationManager.create(getModule());
      Disposer.register(this, myConfigurationManager);
    }

    return myConfigurationManager;
  }

  @Nullable
  public ProjectResources getProjectResources(boolean includeLibraries, boolean createIfNecessary) {
    synchronized (this) {
      if (includeLibraries) {
        if (myProjectResourcesWithLibraries == null && createIfNecessary) {
          myProjectResourcesWithLibraries = ProjectResources.create(this, true /*libraries*/);
        }
        return myProjectResourcesWithLibraries;
      } else {
        if (myProjectResources == null && createIfNecessary) {
          myProjectResources = ProjectResources.create(this, false /*libraries*/);
        }
        return myProjectResources;
      }
    }
  }

  public JpsAndroidModuleProperties getProperties() {
    return getConfiguration().getState();
  }

  /**
   * Associates the given Android-Gradle project to this facet.
   *
   * @param project the new project.
   */
  public void setIdeaAndroidProject(@Nullable IdeaAndroidProject project) {
    myIdeaAndroidProject = project;
    if (project != null && !myGradleProjectAvailableListeners.isEmpty()) {
      // Make copy first since listeners may remove themselves as they are notified, and we
      // don't want a concurrent modification exception
      List<GradleProjectAvailableListener> listeners = new ArrayList<GradleProjectAvailableListener>(myGradleProjectAvailableListeners);
      for (GradleProjectAvailableListener listener : listeners) {
        listener.gradleProjectAvailable(project);
      }
    }
  }

  public void addListener(@NotNull GradleProjectAvailableListener listener) {
    if (myIdeaAndroidProject != null) {
      listener.gradleProjectAvailable(myIdeaAndroidProject);
    }
    synchronized (this) {
      myGradleProjectAvailableListeners.add(listener);
    }
  }

  public void removeListener(@NotNull GradleProjectAvailableListener listener) {
    synchronized (this) {
      myGradleProjectAvailableListeners.remove(listener);
    }
  }

  /**
   * @return the Android-Gradle project associated to this facet.
   */
  @Nullable
  public IdeaAndroidProject getIdeaAndroidProject() {
    return myIdeaAndroidProject;
  }

  public void syncSelectedVariant() {
    if (myIdeaAndroidProject != null) {
      Variant variant = myIdeaAndroidProject.getSelectedVariant();
      JpsAndroidModuleProperties state = getConfiguration().getState();

      ArtifactInfo mainArtifactInfo = variant.getMainArtifactInfo();
      state.ASSEMBLE_TASK_NAME = mainArtifactInfo.getAssembleTaskName();
      state.SOURCE_GEN_TASK_NAME = mainArtifactInfo.getSourceGenTaskName();

      ArtifactInfo testArtifactInfo = variant.getTestArtifactInfo();
      state.ASSEMBLE_TEST_TASK_NAME = testArtifactInfo != null ? testArtifactInfo.getAssembleTaskName() : "";

      state.SELECTED_BUILD_VARIANT = variant.getName();
    }
  }

  // Compatibility bridge for old (non-Gradle) projects
  private class LegacySourceProvider implements SourceProvider {
    @Nullable // TEMPORARY hack; trying to figure out why we're hitting assertions here
    @Override
    public File getManifestFile() {
      final VirtualFile manifestFile = AndroidRootUtil.getManifestFile(AndroidFacet.this);
      return manifestFile == null ? null : VfsUtilCore.virtualToIoFile(manifestFile);
    }

    @NonNull
    @Override
    public Set<File> getJavaDirectories() {
      Set<File> dirs = new HashSet<File>();

      final Module module = getModule();
      final VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
      if (contentRoots.length != 0) {
        for (VirtualFile root : contentRoots) {
          dirs.add(VfsUtilCore.virtualToIoFile(root));
        }
      }
      return dirs;
    }

    @NonNull
    @Override
    public Set<File> getResourcesDirectories() {
      return Collections.emptySet();
    }

    @NonNull
    @Override
    public Set<File> getAidlDirectories() {
      final VirtualFile dir = AndroidRootUtil.getAidlGenDir(AndroidFacet.this);
      assert dir != null;
      return Collections.singleton(VfsUtilCore.virtualToIoFile(dir));
    }

    @NonNull
    @Override
    public Set<File> getRenderscriptDirectories() {
      final VirtualFile dir = AndroidRootUtil.getRenderscriptGenDir(AndroidFacet.this);
      assert dir != null;
      return Collections.singleton(VfsUtilCore.virtualToIoFile(dir));
    }

    @NonNull
    @Override
    public Set<File> getJniDirectories() {
      return Collections.emptySet();
    }

    @NonNull
    @Override
    public Set<File> getResDirectories() {
      String resRelPath = getProperties().RES_FOLDER_RELATIVE_PATH;
      final VirtualFile dir =  AndroidRootUtil.getFileByRelativeModulePath(getModule(), resRelPath, true);
      if (dir != null) {
        return Collections.singleton(VfsUtilCore.virtualToIoFile(dir));
      } else {
        return Collections.emptySet();
      }
    }

    @NonNull
    @Override
    public Set<File> getAssetsDirectories() {
      final VirtualFile dir = AndroidRootUtil.getAssetsDir(AndroidFacet.this);
      assert dir != null;
      return Collections.singleton(VfsUtilCore.virtualToIoFile(dir));
    }
  }

  public interface GradleProjectAvailableListener {
    void gradleProjectAvailable(@NotNull IdeaAndroidProject project);
  }
}
