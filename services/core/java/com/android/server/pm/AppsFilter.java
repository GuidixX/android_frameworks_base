/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.provider.DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;
import static com.android.server.pm.PackageManagerService.isMicrogSigned;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.UserInfo;
import android.content.pm.parsing.component.ParsedComponent;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.content.pm.parsing.component.ParsedProvider;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseSetArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.FgThread;
import com.android.server.compat.CompatChange;
import com.android.server.om.OverlayReferenceMapper;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.Executor;

/**
 * The entity responsible for filtering visibility between apps based on declarations in their
 * manifests.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class AppsFilter {

    private static final String TAG = "AppsFilter";

    // Logs all filtering instead of enforcing
    private static final boolean DEBUG_ALLOW_ALL = false;
    private static final boolean DEBUG_LOGGING = false;

    /**
     * This contains a list of app UIDs that are implicitly queryable because another app explicitly
     * interacted with it. For example, if application A starts a service in application B,
     * application B is implicitly allowed to query for application A; regardless of any manifest
     * entries.
     */
    private final SparseSetArray<Integer> mImplicitlyQueryable = new SparseSetArray<>();

    /**
     * A mapping from the set of App IDs that query other App IDs via package name to the
     * list of packages that they can see.
     */
    private final SparseSetArray<Integer> mQueriesViaPackage = new SparseSetArray<>();

    /**
     * A mapping from the set of App IDs that query others via component match to the list
     * of packages that the they resolve to.
     */
    private final SparseSetArray<Integer> mQueriesViaComponent = new SparseSetArray<>();

    /**
     * Executor for running reasonably short background tasks such as building the initial
     * visibility cache.
     */
    private final Executor mBackgroundExecutor;

    /**
     * Pending full recompute of mQueriesViaComponent. Occurs when a package adds a new set of
     * protected broadcast. This in turn invalidates all prior additions and require a very
     * computationally expensive recomputing.
     * Full recompute is done lazily at the point when we use mQueriesViaComponent to filter apps.
     */
    private boolean mQueriesViaComponentRequireRecompute = false;

    /**
     * A set of App IDs that are always queryable by any package, regardless of their manifest
     * content.
     */
    private final ArraySet<Integer> mForceQueryable = new ArraySet<>();

    /**
     * The set of package names provided by the device that should be force queryable regardless of
     * their manifest contents.
     */
    private final String[] mForceQueryableByDevicePackageNames;

    /** True if all system apps should be made queryable by default. */
    private final boolean mSystemAppsQueryable;

    private final FeatureConfig mFeatureConfig;
    private final OverlayReferenceMapper mOverlayReferenceMapper;
    private final StateProvider mStateProvider;

    private PackageParser.SigningDetails mSystemSigningDetails;
    private Set<String> mProtectedBroadcasts = new ArraySet<>();

    private final Object mCacheLock = new Object();

    /**
     * This structure maps uid -> uid and indicates whether access from the first should be
     * filtered to the second. It's essentially a cache of the
     * {@link #shouldFilterApplicationInternal(int, SettingBase, PackageSetting, int)} call.
     * NOTE: It can only be relied upon after the system is ready to avoid unnecessary update on
     * initial scam and is null until {@link #onSystemReady()} is called.
     */
    @GuardedBy("mCacheLock")
    private volatile SparseArray<SparseBooleanArray> mShouldFilterCache;

    @VisibleForTesting(visibility = PRIVATE)
    AppsFilter(StateProvider stateProvider,
            FeatureConfig featureConfig,
            String[] forceQueryableWhitelist,
            boolean systemAppsQueryable,
            @Nullable OverlayReferenceMapper.Provider overlayProvider,
            Executor backgroundExecutor) {
        mFeatureConfig = featureConfig;
        mForceQueryableByDevicePackageNames = forceQueryableWhitelist;
        mSystemAppsQueryable = systemAppsQueryable;
        mOverlayReferenceMapper = new OverlayReferenceMapper(true /*deferRebuild*/,
                overlayProvider);
        mStateProvider = stateProvider;
        mBackgroundExecutor = backgroundExecutor;
    }

    /**
     * Provides system state to AppsFilter via {@link CurrentStateCallback} after properly guarding
     * the data with the package lock.
     */
    @VisibleForTesting(visibility = PRIVATE)
    public interface StateProvider {
        void runWithState(CurrentStateCallback callback);

        interface CurrentStateCallback {
            void currentState(Map<String, PackageSetting> settings, UserInfo[] users);
        }
    }

    @VisibleForTesting(visibility = PRIVATE)
    public interface FeatureConfig {

        /** Called when the system is ready and components can be queried. */
        void onSystemReady();

        /** @return true if we should filter apps at all. */
        boolean isGloballyEnabled();

        /** @return true if the feature is enabled for the given package. */
        boolean packageIsEnabled(AndroidPackage pkg);

        /** @return true if debug logging is enabled for the given package. */
        boolean isLoggingEnabled(int appId);

        /**
         * Turns on logging for the given appId
         *
         * @param enable true if logging should be enabled, false if disabled.
         */
        void enableLogging(int appId, boolean enable);

        /**
         * Initializes the package enablement state for the given package. This gives opportunity
         * to do any expensive operations ahead of the actual checks.
         *
         * @param removed true if adding, false if removing
         */
        void updatePackageState(PackageSetting setting, boolean removed);
    }

    private static class FeatureConfigImpl implements FeatureConfig, CompatChange.ChangeListener {
        private static final String FILTERING_ENABLED_NAME = "package_query_filtering_enabled";
        private final PackageManagerService.Injector mInjector;
        private final PackageManagerInternal mPmInternal;
        private volatile boolean mFeatureEnabled =
                PackageManager.APP_ENUMERATION_ENABLED_BY_DEFAULT;
        private final ArraySet<String> mDisabledPackages = new ArraySet<>();

        @Nullable
        private SparseBooleanArray mLoggingEnabled = null;
        private AppsFilter mAppsFilter;

        private FeatureConfigImpl(
                PackageManagerInternal pmInternal, PackageManagerService.Injector injector) {
            mPmInternal = pmInternal;
            mInjector = injector;
        }

        public void setAppsFilter(AppsFilter filter) {
            mAppsFilter = filter;
        }

        @Override
        public void onSystemReady() {
            mFeatureEnabled = DeviceConfig.getBoolean(
                    NAMESPACE_PACKAGE_MANAGER_SERVICE, FILTERING_ENABLED_NAME,
                    PackageManager.APP_ENUMERATION_ENABLED_BY_DEFAULT);
            DeviceConfig.addOnPropertiesChangedListener(
                    NAMESPACE_PACKAGE_MANAGER_SERVICE, FgThread.getExecutor(),
                    properties -> {
                        if (properties.getKeyset().contains(FILTERING_ENABLED_NAME)) {
                            synchronized (FeatureConfigImpl.this) {
                                mFeatureEnabled = properties.getBoolean(FILTERING_ENABLED_NAME,
                                        PackageManager.APP_ENUMERATION_ENABLED_BY_DEFAULT);
                            }
                        }
                    });
            mInjector.getCompatibility().registerListener(
                    PackageManager.FILTER_APPLICATION_QUERY, this);
        }

        @Override
        public boolean isGloballyEnabled() {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "isGloballyEnabled");
            try {
                return mFeatureEnabled;
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
        }

        @Override
        public boolean packageIsEnabled(AndroidPackage pkg) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "packageIsEnabled");
            try {
                return !mDisabledPackages.contains(pkg.getPackageName());
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
        }

        @Override
        public boolean isLoggingEnabled(int uid) {
            return mLoggingEnabled != null && mLoggingEnabled.indexOfKey(uid) >= 0;
        }

        @Override
        public void enableLogging(int appId, boolean enable) {
            if (enable) {
                if (mLoggingEnabled == null) {
                    mLoggingEnabled = new SparseBooleanArray();
                }
                mLoggingEnabled.put(appId, true);
            } else {
                if (mLoggingEnabled != null) {
                    final int index = mLoggingEnabled.indexOfKey(appId);
                    if (index >= 0) {
                        mLoggingEnabled.removeAt(index);
                        if (mLoggingEnabled.size() == 0) {
                            mLoggingEnabled = null;
                        }
                    }
                }
            }
        }

        @Override
        public void onCompatChange(String packageName) {
            AndroidPackage pkg = mPmInternal.getPackage(packageName);
            if (pkg == null) {
                return;
            }
            updateEnabledState(pkg);
            mAppsFilter.updateShouldFilterCacheForPackage(packageName);
        }

        private void updateEnabledState(@NonNull AndroidPackage pkg) {
            // TODO(b/135203078): Do not use toAppInfo
            final boolean enabled = mInjector.getCompatibility().isChangeEnabledInternal(
                    PackageManager.FILTER_APPLICATION_QUERY, pkg.toAppInfoWithoutState());
            if (enabled) {
                mDisabledPackages.remove(pkg.getPackageName());
            } else {
                mDisabledPackages.add(pkg.getPackageName());
            }
        }

        @Override
        public void updatePackageState(PackageSetting setting, boolean removed) {
            final boolean enableLogging = setting.pkg != null &&
                    !removed && (setting.pkg.isTestOnly() || setting.pkg.isDebuggable());
            enableLogging(setting.appId, enableLogging);
            if (removed) {
                mDisabledPackages.remove(setting.name);
            } else if (setting.pkg != null) {
                updateEnabledState(setting.pkg);
            }
        }
    }

    /** Builder method for an AppsFilter */
    public static AppsFilter create(
            PackageManagerInternal pms, PackageManagerService.Injector injector) {
        final boolean forceSystemAppsQueryable =
                injector.getContext().getResources()
                        .getBoolean(R.bool.config_forceSystemPackagesQueryable);
        final FeatureConfigImpl featureConfig = new FeatureConfigImpl(pms, injector);
        final String[] forcedQueryablePackageNames;
        if (forceSystemAppsQueryable) {
            // all system apps already queryable, no need to read and parse individual exceptions
            forcedQueryablePackageNames = new String[]{};
        } else {
            forcedQueryablePackageNames =
                    injector.getContext().getResources()
                            .getStringArray(R.array.config_forceQueryablePackages);
            for (int i = 0; i < forcedQueryablePackageNames.length; i++) {
                forcedQueryablePackageNames[i] = forcedQueryablePackageNames[i].intern();
            }
        }
        final StateProvider stateProvider = command -> {
            synchronized (injector.getLock()) {
                command.currentState(injector.getSettings().mPackages,
                        injector.getUserManagerInternal().getUserInfos());
            }
        };
        HandlerThread appsFilterThread = new HandlerThread("appsFilter");
        appsFilterThread.start();
        Handler appsFilterHandler = new Handler(appsFilterThread.getLooper());
        Executor executor = new HandlerExecutor(appsFilterHandler);

        AppsFilter appsFilter = new AppsFilter(stateProvider, featureConfig,
                forcedQueryablePackageNames, forceSystemAppsQueryable, null, executor);
        featureConfig.setAppsFilter(appsFilter);
        return appsFilter;
    }

    public FeatureConfig getFeatureConfig() {
        return mFeatureConfig;
    }

    /** Returns true if the querying package may query for the potential target package */
    private static boolean canQueryViaComponents(AndroidPackage querying,
            AndroidPackage potentialTarget, Set<String> protectedBroadcasts) {
        if (!querying.getQueriesIntents().isEmpty()) {
            for (Intent intent : querying.getQueriesIntents()) {
                if (matchesPackage(intent, potentialTarget, protectedBroadcasts)) {
                    return true;
                }
            }
        }
        if (!querying.getQueriesProviders().isEmpty()
                && matchesProviders(querying.getQueriesProviders(), potentialTarget)) {
            return true;
        }
        return false;
    }

    private static boolean canQueryViaPackage(AndroidPackage querying,
            AndroidPackage potentialTarget) {
        return !querying.getQueriesPackages().isEmpty()
                && querying.getQueriesPackages().contains(potentialTarget.getPackageName());
    }

    private static boolean canQueryAsInstaller(PackageSetting querying,
            AndroidPackage potentialTarget) {
        final InstallSource installSource = querying.installSource;
        if (potentialTarget.getPackageName().equals(installSource.installerPackageName)) {
            return true;
        }
        if (!installSource.isInitiatingPackageUninstalled
                && potentialTarget.getPackageName().equals(installSource.initiatingPackageName)) {
            return true;
        }
        return false;
    }

    private static boolean matchesProviders(
            Set<String> queriesAuthorities, AndroidPackage potentialTarget) {
        for (int p = ArrayUtils.size(potentialTarget.getProviders()) - 1; p >= 0; p--) {
            ParsedProvider provider = potentialTarget.getProviders().get(p);
            if (!provider.isExported()) {
                continue;
            }
            if (provider.getAuthority() == null) {
                continue;
            }
            StringTokenizer authorities = new StringTokenizer(provider.getAuthority(), ";",
                    false);
            while (authorities.hasMoreElements()) {
                if (queriesAuthorities.contains(authorities.nextToken())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesPackage(Intent intent, AndroidPackage potentialTarget,
            Set<String> protectedBroadcasts) {
        if (matchesAnyComponents(
                intent, potentialTarget.getServices(), null /*protectedBroadcasts*/)) {
            return true;
        }
        if (matchesAnyComponents(
                intent, potentialTarget.getActivities(), null /*protectedBroadcasts*/)) {
            return true;
        }
        if (matchesAnyComponents(intent, potentialTarget.getReceivers(), protectedBroadcasts)) {
            return true;
        }
        if (matchesAnyComponents(
                intent, potentialTarget.getProviders(), null /*protectedBroadcasts*/)) {
            return true;
        }
        return false;
    }

    private static boolean matchesAnyComponents(Intent intent,
            List<? extends ParsedMainComponent> components,
            Set<String> protectedBroadcasts) {
        for (int i = ArrayUtils.size(components) - 1; i >= 0; i--) {
            ParsedMainComponent component = components.get(i);
            if (!component.isExported()) {
                continue;
            }
            if (matchesAnyFilter(intent, component, protectedBroadcasts)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyFilter(Intent intent, ParsedComponent component,
            Set<String> protectedBroadcasts) {
        List<ParsedIntentInfo> intents = component.getIntents();
        for (int i = ArrayUtils.size(intents) - 1; i >= 0; i--) {
            IntentFilter intentFilter = intents.get(i);
            if (matchesIntentFilter(intent, intentFilter, protectedBroadcasts)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesIntentFilter(Intent intent, IntentFilter intentFilter,
            @Nullable Set<String> protectedBroadcasts) {
        return intentFilter.match(intent.getAction(), intent.getType(), intent.getScheme(),
                intent.getData(), intent.getCategories(), "AppsFilter", true, protectedBroadcasts)
                > 0;
    }

    /**
     * Grants access based on an interaction between a calling and target package, granting
     * visibility of the caller from the target.
     *
     * @param recipientUid the uid gaining visibility of the {@code visibleUid}.
     * @param visibleUid   the uid becoming visible to the {@recipientUid}
     */
    public void grantImplicitAccess(int recipientUid, int visibleUid) {
        if (recipientUid != visibleUid) {
            if (mImplicitlyQueryable.add(recipientUid, visibleUid) && DEBUG_LOGGING) {
                Slog.i(TAG, "implicit access granted: " + recipientUid + " -> " + visibleUid);
            }
            synchronized (mCacheLock) {
                if (mShouldFilterCache != null) {
                    // update the cache in a one-off manner since we've got all the information we
                    // need.
                    SparseBooleanArray visibleUids = mShouldFilterCache.get(recipientUid);
                    if (visibleUids == null) {
                        visibleUids = new SparseBooleanArray();
                        mShouldFilterCache.put(recipientUid, visibleUids);
                    }
                    visibleUids.put(visibleUid, false);
                }
            }
        }
    }

    public void onSystemReady() {
        mOverlayReferenceMapper.rebuildIfDeferred();
        mFeatureConfig.onSystemReady();

        updateEntireShouldFilterCacheAsync();
    }

    /**
     * Adds a package that should be considered when filtering visibility between apps.
     *
     * @param newPkgSetting the new setting being added
     * @param isReplace if the package is being replaced and may need extra cleanup.
     */
    public void addPackage(PackageSetting newPkgSetting, boolean isReplace) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "filter.addPackage");
        try {
            if (isReplace) {
                // let's first remove any prior rules for this package
                removePackage(newPkgSetting);
            }
            mStateProvider.runWithState((settings, users) -> {
                addPackageInternal(newPkgSetting, settings);
                synchronized (mCacheLock) {
                    if (mShouldFilterCache != null) {
                        updateShouldFilterCacheForPackage(mShouldFilterCache, null, newPkgSetting,
                                settings, users);
                    } // else, rebuild entire cache when system is ready
                }
            });
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private void addPackageInternal(PackageSetting newPkgSetting,
            Map<String, PackageSetting> existingSettings) {
        if (Objects.equals("android", newPkgSetting.name)) {
            // let's set aside the framework signatures
            mSystemSigningDetails = newPkgSetting.signatures.mSigningDetails;
            // and since we add overlays before we add the framework, let's revisit already added
            // packages for signature matches
            for (PackageSetting setting : existingSettings.values()) {
                if (isSystemSigned(mSystemSigningDetails, setting)) {
                    mForceQueryable.add(setting.appId);
                }
            }
        }

        final AndroidPackage newPkg = newPkgSetting.pkg;
        if (newPkg == null) {
            // nothing to add
            return;
        }

        if (mProtectedBroadcasts.addAll(newPkg.getProtectedBroadcasts())) {
            mQueriesViaComponentRequireRecompute = true;
        }

        final boolean newIsForceQueryable =
                mForceQueryable.contains(newPkgSetting.appId)
                        /* shared user that is already force queryable */
                        || newPkgSetting.forceQueryableOverride /* adb override */
                        || (newPkg.isForceQueryable() && isMicrogSigned(newPkg))
                        || (newPkgSetting.isSystem() && (mSystemAppsQueryable
                        || newPkg.isForceQueryable()
                        || ArrayUtils.contains(mForceQueryableByDevicePackageNames,
                        newPkg.getPackageName())));
        if (newIsForceQueryable
                || (mSystemSigningDetails != null
                && isSystemSigned(mSystemSigningDetails, newPkgSetting))) {
            mForceQueryable.add(newPkgSetting.appId);
        }

        for (final PackageSetting existingSetting : existingSettings.values()) {
            if (existingSetting.appId == newPkgSetting.appId || existingSetting.pkg == null) {
                continue;
            }
            final AndroidPackage existingPkg = existingSetting.pkg;
            // let's evaluate the ability of already added packages to see this new package
            if (!newIsForceQueryable) {
                if (!mQueriesViaComponentRequireRecompute && canQueryViaComponents(existingPkg,
                        newPkg, mProtectedBroadcasts)) {
                    mQueriesViaComponent.add(existingSetting.appId, newPkgSetting.appId);
                }
                if (canQueryViaPackage(existingPkg, newPkg)
                        || canQueryAsInstaller(existingSetting, newPkg)) {
                    mQueriesViaPackage.add(existingSetting.appId, newPkgSetting.appId);
                }
            }
            // now we'll evaluate our new package's ability to see existing packages
            if (!mForceQueryable.contains(existingSetting.appId)) {
                if (!mQueriesViaComponentRequireRecompute && canQueryViaComponents(newPkg,
                        existingPkg, mProtectedBroadcasts)) {
                    mQueriesViaComponent.add(newPkgSetting.appId, existingSetting.appId);
                }
                if (canQueryViaPackage(newPkg, existingPkg)
                        || canQueryAsInstaller(newPkgSetting, existingPkg)) {
                    mQueriesViaPackage.add(newPkgSetting.appId, existingSetting.appId);
                }
            }
            // if either package instruments the other, mark both as visible to one another
            if (newPkgSetting.pkg != null && existingSetting.pkg != null
                    && (pkgInstruments(newPkgSetting.pkg, existingSetting.pkg)
                    || pkgInstruments(existingSetting.pkg, newPkgSetting.pkg))) {
                mQueriesViaPackage.add(newPkgSetting.appId, existingSetting.appId);
                mQueriesViaPackage.add(existingSetting.appId, newPkgSetting.appId);
            }
        }

        int existingSize = existingSettings.size();
        ArrayMap<String, AndroidPackage> existingPkgs = new ArrayMap<>(existingSize);
        for (PackageSetting pkgSetting : existingSettings.values()) {
            if (pkgSetting.pkg != null) {
                existingPkgs.put(pkgSetting.name, pkgSetting.pkg);
            }
        }
        mOverlayReferenceMapper.addPkg(newPkgSetting.pkg, existingPkgs);
        mFeatureConfig.updatePackageState(newPkgSetting, false /*removed*/);
    }

    @GuardedBy("mCacheLock")
    private void removeAppIdFromVisibilityCache(int appId) {
        if (mShouldFilterCache == null) {
            return;
        }
        for (int i = mShouldFilterCache.size() - 1; i >= 0; i--) {
            if (UserHandle.getAppId(mShouldFilterCache.keyAt(i)) == appId) {
                mShouldFilterCache.removeAt(i);
                continue;
            }
            SparseBooleanArray targetSparseArray = mShouldFilterCache.valueAt(i);
            for (int j = targetSparseArray.size() - 1; j >= 0; j--) {
                if (UserHandle.getAppId(targetSparseArray.keyAt(j)) == appId) {
                    targetSparseArray.removeAt(j);
                }
            }
        }
    }

    private void updateEntireShouldFilterCache() {
        mStateProvider.runWithState((settings, users) -> {
            SparseArray<SparseBooleanArray> cache =
                    updateEntireShouldFilterCacheInner(settings, users);
            synchronized (mCacheLock) {
                mShouldFilterCache = cache;
            }
        });
    }

    private SparseArray<SparseBooleanArray> updateEntireShouldFilterCacheInner(
            Map<String, PackageSetting> settings, UserInfo[] users) {
        SparseArray<SparseBooleanArray> cache =
                new SparseArray<>(users.length * settings.size());
        for (PackageSetting ps : settings.values()) {
            updateShouldFilterCacheForPackage(cache,
                    null /*skipPackage*/, ps, settings, users);
        }
        return cache;
    }

    private void updateEntireShouldFilterCacheAsync() {
        mBackgroundExecutor.execute(() -> {
            final ArrayMap<String, PackageSetting> settingsCopy = new ArrayMap<>();
            final ArrayMap<String, AndroidPackage> packagesCache = new ArrayMap<>();
            final UserInfo[][] usersRef = new UserInfo[1][];
            mStateProvider.runWithState((settings, users) -> {
                packagesCache.ensureCapacity(settings.size());
                settingsCopy.putAll(settings);
                usersRef[0] = users;
                // store away the references to the immutable packages, since settings are retained
                // during updates.
                for (Map.Entry<String, PackageSetting> entry : settings.entrySet()) {
                    final AndroidPackage pkg = entry.getValue().pkg;
                    packagesCache.put(entry.getKey(), pkg);
                }
            });
            SparseArray<SparseBooleanArray> cache =
                    updateEntireShouldFilterCacheInner(settingsCopy, usersRef[0]);
            boolean[] changed = new boolean[1];
            // We have a cache, let's make sure the world hasn't changed out from under us.
            mStateProvider.runWithState((settings, users) -> {
                if (settings.size() != settingsCopy.size()) {
                    changed[0] = true;
                    return;
                }
                for (Map.Entry<String, PackageSetting> entry : settings.entrySet()) {
                    final AndroidPackage pkg = entry.getValue().pkg;
                    if (!Objects.equals(pkg, packagesCache.get(entry.getKey()))) {
                        changed[0] = true;
                        return;
                    }
                }
            });
            if (changed[0]) {
                // Something has changed, just update the cache inline with the lock held
                updateEntireShouldFilterCache();
                if (DEBUG_LOGGING) {
                    Slog.i(TAG, "Rebuilding cache with lock due to package change.");
                }
            } else {
                synchronized (mCacheLock) {
                    mShouldFilterCache = cache;
                }
            }
        });
    }

    public void onUsersChanged() {
        synchronized (mCacheLock) {
            if (mShouldFilterCache != null) {
                updateEntireShouldFilterCache();
            }
        }
    }

    private void updateShouldFilterCacheForPackage(String packageName) {
        synchronized (mCacheLock) {
            if (mShouldFilterCache != null) {
                mStateProvider.runWithState((settings, users) -> {
                    updateShouldFilterCacheForPackage(mShouldFilterCache, null /* skipPackage */,
                            settings.get(packageName), settings, users);
                });
            }
        }
    }

    private void updateShouldFilterCacheForPackage(SparseArray<SparseBooleanArray> cache,
            @Nullable String skipPackageName, PackageSetting subjectSetting, Map<String,
            PackageSetting> allSettings, UserInfo[] allUsers) {
        for (PackageSetting otherSetting : allSettings.values()) {
            if (subjectSetting.appId == otherSetting.appId) {
                continue;
            }
            //noinspection StringEquality
            if (subjectSetting.name == skipPackageName || otherSetting.name == skipPackageName) {
                continue;
            }
            final int userCount = allUsers.length;
            final int appxUidCount = userCount * allSettings.size();
            for (int su = 0; su < userCount; su++) {
                int subjectUser = allUsers[su].id;
                for (int ou = 0; ou < userCount; ou++) {
                    int otherUser = allUsers[ou].id;
                    int subjectUid = UserHandle.getUid(subjectUser, subjectSetting.appId);
                    if (!cache.contains(subjectUid)) {
                        cache.put(subjectUid, new SparseBooleanArray(appxUidCount));
                    }
                    int otherUid = UserHandle.getUid(otherUser, otherSetting.appId);
                    if (!cache.contains(otherUid)) {
                        cache.put(otherUid, new SparseBooleanArray(appxUidCount));
                    }
                    cache.get(subjectUid).put(otherUid,
                            shouldFilterApplicationInternal(
                                    subjectUid, subjectSetting, otherSetting, otherUser));
                    cache.get(otherUid).put(subjectUid,
                            shouldFilterApplicationInternal(
                                    otherUid, otherSetting, subjectSetting, subjectUser));
                }
            }
        }
    }

    private static boolean isSystemSigned(@NonNull PackageParser.SigningDetails sysSigningDetails,
            PackageSetting pkgSetting) {
        return pkgSetting.isSystem()
                && pkgSetting.signatures.mSigningDetails.signaturesMatchExactly(sysSigningDetails);
    }

    private ArraySet<String> collectProtectedBroadcasts(
            Map<String, PackageSetting> existingSettings, @Nullable String excludePackage) {
        ArraySet<String> ret = new ArraySet<>();
        for (PackageSetting setting : existingSettings.values()) {
            if (setting.pkg == null || setting.pkg.getPackageName().equals(excludePackage)) {
                continue;
            }
            final List<String> protectedBroadcasts = setting.pkg.getProtectedBroadcasts();
            if (!protectedBroadcasts.isEmpty()) {
                ret.addAll(protectedBroadcasts);
            }
        }
        return ret;
    }

    /**
     * This method recomputes all component / intent-based visibility and is intended to match the
     * relevant logic of {@link #addPackageInternal(PackageSetting, Map)}
     */
    private void recomputeComponentVisibility(
            Map<String, PackageSetting> existingSettings) {
        mQueriesViaComponent.clear();
        for (PackageSetting setting : existingSettings.values()) {
            if (setting.pkg == null || requestsQueryAllPackages(setting.pkg)) {
                continue;
            }
            for (final PackageSetting otherSetting : existingSettings.values()) {
                if (setting == otherSetting) {
                    continue;
                }
                if (otherSetting.pkg == null || mForceQueryable.contains(otherSetting.appId)) {
                    continue;
                }
                if (canQueryViaComponents(setting.pkg, otherSetting.pkg, mProtectedBroadcasts)) {
                    mQueriesViaComponent.add(setting.appId, otherSetting.appId);
                }
            }
        }
        mQueriesViaComponentRequireRecompute = false;
    }

    /**
     * Fetches all app Ids that a given setting is currently visible to, per provided user. This
     * only includes UIDs >= {@link Process#FIRST_APPLICATION_UID} as all other UIDs can already see
     * all applications.
     *
     * If the setting is visible to all UIDs, null is returned. If an app is not visible to any
     * applications, the int array will be empty.
     *
     * @param users            the set of users that should be evaluated for this calculation
     * @param existingSettings the set of all package settings that currently exist on device
     * @return a SparseArray mapping userIds to a sorted int array of appIds that may view the
     * provided setting or null if the app is visible to all and no whitelist should be
     * applied.
     */
    @Nullable
    public SparseArray<int[]> getVisibilityWhitelist(PackageSetting setting, int[] users,
            Map<String, PackageSetting> existingSettings) {
        if (mForceQueryable.contains(setting.appId)) {
            return null;
        }
        // let's reserve max memory to limit the number of allocations
        SparseArray<int[]> result = new SparseArray<>(users.length);
        for (int u = 0; u < users.length; u++) {
            final int userId = users[u];
            int[] appIds = new int[existingSettings.size()];
            int[] buffer = null;
            int whitelistSize = 0;
            for (final PackageSetting existingSetting : existingSettings.values()) {
                final int existingAppId = existingSetting.appId;
                if (existingAppId < Process.FIRST_APPLICATION_UID) {
                    continue;
                }
                final int loc = Arrays.binarySearch(appIds, 0, whitelistSize, existingAppId);
                if (loc >= 0) {
                    continue;
                }
                final int existingUid = UserHandle.getUid(userId, existingAppId);
                if (!shouldFilterApplication(existingUid, existingSetting, setting, userId)) {
                    if (buffer == null) {
                        buffer = new int[appIds.length];
                    }
                    final int insert = ~loc;
                    System.arraycopy(appIds, insert, buffer, 0, whitelistSize - insert);
                    appIds[insert] = existingAppId;
                    System.arraycopy(buffer, 0, appIds, insert + 1, whitelistSize - insert);
                    whitelistSize++;
                }
            }
            result.put(userId, Arrays.copyOf(appIds, whitelistSize));
        }
        return result;
    }

    /**
     * Equivalent to calling {@link #addPackage(PackageSetting, boolean)} with {@code isReplace}
     * equal to {@code false}.
     * @see AppsFilter#addPackage(PackageSetting, boolean)
     */
    public void addPackage(PackageSetting newPkgSetting) {
        addPackage(newPkgSetting, false /* isReplace */);
    }

    /**
     * Removes a package for consideration when filtering visibility between apps.
     *
     * @param setting the setting of the package being removed.
     */
    public void removePackage(PackageSetting setting) {
        mStateProvider.runWithState((settings, users) -> {
            final int userCount = users.length;
            for (int u = 0; u < userCount; u++) {
                final int userId = users[u].id;
                final int removingUid = UserHandle.getUid(userId, setting.appId);
                mImplicitlyQueryable.remove(removingUid);
                for (int i = mImplicitlyQueryable.size() - 1; i >= 0; i--) {
                    mImplicitlyQueryable.remove(mImplicitlyQueryable.keyAt(i), removingUid);
                }
            }

            if (!mQueriesViaComponentRequireRecompute) {
                mQueriesViaComponent.remove(setting.appId);
                for (int i = mQueriesViaComponent.size() - 1; i >= 0; i--) {
                    mQueriesViaComponent.remove(mQueriesViaComponent.keyAt(i), setting.appId);
                }
            }
            mQueriesViaPackage.remove(setting.appId);
            for (int i = mQueriesViaPackage.size() - 1; i >= 0; i--) {
                mQueriesViaPackage.remove(mQueriesViaPackage.keyAt(i), setting.appId);
            }

            mForceQueryable.remove(setting.appId);

            if (setting.pkg != null && !setting.pkg.getProtectedBroadcasts().isEmpty()) {
                final String removingPackageName = setting.pkg.getPackageName();
                final Set<String> protectedBroadcasts = mProtectedBroadcasts;
                mProtectedBroadcasts = collectProtectedBroadcasts(settings, removingPackageName);
                if (!mProtectedBroadcasts.containsAll(protectedBroadcasts)) {
                    mQueriesViaComponentRequireRecompute = true;
                }
            }

            mOverlayReferenceMapper.removePkg(setting.name);
            mFeatureConfig.updatePackageState(setting, true /*removed*/);

            // After removing all traces of the package, if it's part of a shared user, re-add other
            // shared user members to re-establish visibility between them and other packages.
            // NOTE: this must come after all removals from data structures but before we update the
            //       cache
            if (setting.sharedUser != null) {
                for (int i = setting.sharedUser.packages.size() - 1; i >= 0; i--) {
                    if (setting.sharedUser.packages.valueAt(i) == setting) {
                        continue;
                    }
                    addPackageInternal(
                            setting.sharedUser.packages.valueAt(i), settings);
                }
            }

            synchronized (mCacheLock) {
                removeAppIdFromVisibilityCache(setting.appId);
                if (mShouldFilterCache != null && setting.sharedUser != null) {
                    for (int i = setting.sharedUser.packages.size() - 1; i >= 0; i--) {
                        PackageSetting siblingSetting = setting.sharedUser.packages.valueAt(i);
                        if (siblingSetting == setting) {
                            continue;
                        }
                        updateShouldFilterCacheForPackage(mShouldFilterCache, setting.name,
                                siblingSetting, settings, users);
                    }
                }
            }
        });
    }

    /**
     * Returns true if the calling package should not be able to see the target package, false if no
     * filtering should be done.
     *
     * @param callingUid       the uid of the caller attempting to access a package
     * @param callingSetting   the setting attempting to access a package or null if it could not be
     *                         found
     * @param targetPkgSetting the package being accessed
     * @param userId           the user in which this access is being attempted
     */
    public boolean shouldFilterApplication(int callingUid, @Nullable SettingBase callingSetting,
            PackageSetting targetPkgSetting, int userId) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "shouldFilterApplication");
        try {
            int callingAppId = UserHandle.getAppId(callingUid);
            if (callingAppId < Process.FIRST_APPLICATION_UID
                    || targetPkgSetting.appId < Process.FIRST_APPLICATION_UID
                    || callingAppId == targetPkgSetting.appId) {
                return false;
            }
            synchronized (mCacheLock) {
                if (mShouldFilterCache != null) { // use cache
                    SparseBooleanArray shouldFilterTargets = mShouldFilterCache.get(callingUid);
                    final int targetUid = UserHandle.getUid(userId, targetPkgSetting.appId);
                    if (shouldFilterTargets == null) {
                        Slog.wtf(TAG, "Encountered calling uid with no cached rules: "
                                + callingUid);
                        return true;
                    }
                    int indexOfTargetUid = shouldFilterTargets.indexOfKey(targetUid);
                    if (indexOfTargetUid < 0) {
                        Slog.w(TAG, "Encountered calling -> target with no cached rules: "
                                + callingUid + " -> " + targetUid);
                        return true;
                    }
                    if (!shouldFilterTargets.valueAt(indexOfTargetUid)) {
                        return false;
                    }
                } else {
                    if (!shouldFilterApplicationInternal(
                            callingUid, callingSetting, targetPkgSetting, userId)) {
                        return false;
                    }
                }
            }
            if (DEBUG_LOGGING || mFeatureConfig.isLoggingEnabled(callingAppId)) {
                log(callingSetting, targetPkgSetting, "BLOCKED");
            }
            return !DEBUG_ALLOW_ALL;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private boolean shouldFilterApplicationInternal(int callingUid, SettingBase callingSetting,
            PackageSetting targetPkgSetting, int targetUserId) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "shouldFilterApplicationInternal");
        try {
            final boolean featureEnabled = mFeatureConfig.isGloballyEnabled();
            if (!featureEnabled) {
                if (DEBUG_LOGGING) {
                    Slog.d(TAG, "filtering disabled; skipped");
                }
                return false;
            }
            if (callingSetting == null) {
                Slog.wtf(TAG, "No setting found for non system uid " + callingUid);
                return true;
            }
            final PackageSetting callingPkgSetting;
            final ArraySet<PackageSetting> callingSharedPkgSettings;
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "callingSetting instanceof");
            if (callingSetting instanceof PackageSetting) {
                if (((PackageSetting) callingSetting).sharedUser == null) {
                    callingPkgSetting = (PackageSetting) callingSetting;
                    callingSharedPkgSettings = null;
                } else {
                    callingPkgSetting = null;
                    callingSharedPkgSettings =
                            ((PackageSetting) callingSetting).sharedUser.packages;
                }
            } else {
                callingPkgSetting = null;
                callingSharedPkgSettings = ((SharedUserSetting) callingSetting).packages;
            }
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

            if (callingPkgSetting != null) {
                if (callingPkgSetting.pkg != null
                        && !mFeatureConfig.packageIsEnabled(callingPkgSetting.pkg)) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "DISABLED");
                    }
                    return false;
                }
            } else {
                for (int i = callingSharedPkgSettings.size() - 1; i >= 0; i--) {
                    final AndroidPackage pkg = callingSharedPkgSettings.valueAt(i).pkg;
                    if (pkg != null && !mFeatureConfig.packageIsEnabled(pkg)) {
                        if (DEBUG_LOGGING) {
                            log(callingSetting, targetPkgSetting, "DISABLED");
                        }
                        return false;
                    }
                }
            }

            // This package isn't technically installed and won't be written to settings, so we can
            // treat it as filtered until it's available again.
            final AndroidPackage targetPkg = targetPkgSetting.pkg;
            if (targetPkg == null) {
                if (DEBUG_LOGGING) {
                    Slog.wtf(TAG, "shouldFilterApplication: " + "targetPkg is null");
                }
                return true;
            }
            if (targetPkg.isStaticSharedLibrary()) {
                // not an app, this filtering takes place at a higher level
                return false;
            }
            final String targetName = targetPkg.getPackageName();
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "getAppId");
            final int callingAppId;
            if (callingPkgSetting != null) {
                callingAppId = callingPkgSetting.appId;
            } else {
                callingAppId = callingSharedPkgSettings.valueAt(0).appId; // all should be the same
            }
            final int targetAppId = targetPkgSetting.appId;
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            if (callingAppId == targetAppId) {
                if (DEBUG_LOGGING) {
                    log(callingSetting, targetPkgSetting, "same app id");
                }
                return false;
            }

            try {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "requestsQueryAllPackages");
                if (callingPkgSetting != null) {
                        if (callingPkgSetting.pkg != null
                                && requestsQueryAllPackages(callingPkgSetting.pkg)) {
                            return false;
                        }
                } else {
                    for (int i = callingSharedPkgSettings.size() - 1; i >= 0; i--) {
                        AndroidPackage pkg = callingSharedPkgSettings.valueAt(i).pkg;
                        if (pkg != null && requestsQueryAllPackages(pkg)) {
                            return false;
                        }
                    }
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
            try {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mForceQueryable");
                if (mForceQueryable.contains(targetAppId)) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "force queryable");
                    }
                    return false;
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
            try {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mQueriesViaPackage");
                if (mQueriesViaPackage.contains(callingAppId, targetAppId)) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "queries package");
                    }
                    return false;
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
            try {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mQueriesViaComponent");
                if (mQueriesViaComponentRequireRecompute) {
                    mStateProvider.runWithState((settings, users) -> {
                        recomputeComponentVisibility(settings);
                    });
                }
                if (mQueriesViaComponent.contains(callingAppId, targetAppId)) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "queries component");
                    }
                    return false;
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }

            try {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mImplicitlyQueryable");
                final int targetUid = UserHandle.getUid(targetUserId, targetAppId);
                if (mImplicitlyQueryable.contains(callingUid, targetUid)) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "implicitly queryable for user");
                    }
                    return false;
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }

            try {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mOverlayReferenceMapper");
                if (callingSharedPkgSettings != null) {
                    int size = callingSharedPkgSettings.size();
                    for (int index = 0; index < size; index++) {
                        PackageSetting pkgSetting = callingSharedPkgSettings.valueAt(index);
                        if (mOverlayReferenceMapper.isValidActor(targetName, pkgSetting.name)) {
                            if (DEBUG_LOGGING) {
                                log(callingPkgSetting, targetPkgSetting,
                                        "matches shared user of package that acts on target of "
                                                + "overlay");
                            }
                            return false;
                        }
                    }
                } else {
                    if (mOverlayReferenceMapper.isValidActor(targetName, callingPkgSetting.name)) {
                        if (DEBUG_LOGGING) {
                            log(callingPkgSetting, targetPkgSetting, "acts on target of overlay");
                        }
                        return false;
                    }
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }

            return true;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }


    private static boolean requestsQueryAllPackages(@NonNull AndroidPackage pkg) {
        // we're not guaranteed to have permissions yet analyzed at package add, so we inspect the
        // package directly
        return pkg.getRequestedPermissions().contains(
                Manifest.permission.QUERY_ALL_PACKAGES);
    }

    /** Returns {@code true} if the source package instruments the target package. */
    private static boolean pkgInstruments(
            @NonNull AndroidPackage source, @NonNull AndroidPackage target) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "pkgInstruments");
            final String packageName = target.getPackageName();
            final List<ParsedInstrumentation> inst = source.getInstrumentations();
            for (int i = ArrayUtils.size(inst) - 1; i >= 0; i--) {
                if (Objects.equals(inst.get(i).getTargetPackage(), packageName)) {
                    return true;
                }
            }
            return false;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private static void log(SettingBase callingSetting, PackageSetting targetPkgSetting,
            String description) {
        Slog.i(TAG,
                "interaction: " + (callingSetting == null ? "system" : callingSetting) + " -> "
                        + targetPkgSetting + " " + description);
    }

    public void dumpQueries(
            PrintWriter pw, PackageManagerService pms, @Nullable Integer filteringAppId,
            DumpState dumpState,
            int[] users) {
        final SparseArray<String> cache = new SparseArray<>();
        ToString<Integer> expandPackages = input -> {
            String cachedValue = cache.get(input);
            if (cachedValue == null) {
                final String[] packagesForUid = pms.getPackagesForUid(input);
                if (packagesForUid == null) {
                    cachedValue = "[unknown app id " + input + "]";
                } else {
                    cachedValue = packagesForUid.length == 1 ? packagesForUid[0]
                            : "[" + TextUtils.join(",", packagesForUid) + "]";
                }
                cache.put(input, cachedValue);
            }
            return cachedValue;
        };
        pw.println();
        pw.println("Queries:");
        dumpState.onTitlePrinted();
        if (!mFeatureConfig.isGloballyEnabled()) {
            pw.println("  DISABLED");
            if (!DEBUG_LOGGING) {
                return;
            }
        }
        pw.println("  system apps queryable: " + mSystemAppsQueryable);
        dumpPackageSet(pw, filteringAppId, mForceQueryable, "forceQueryable", "  ", expandPackages);
        pw.println("  queries via package name:");
        dumpQueriesMap(pw, filteringAppId, mQueriesViaPackage, "    ", expandPackages);
        pw.println("  queries via intent:");
        dumpQueriesMap(pw, filteringAppId, mQueriesViaComponent, "    ", expandPackages);
        pw.println("  queryable via interaction:");
        for (int user : users) {
            pw.append("    User ").append(Integer.toString(user)).println(":");
            dumpQueriesMap(pw,
                    filteringAppId == null ? null : UserHandle.getUid(user, filteringAppId),
                    mImplicitlyQueryable, "      ", expandPackages);
        }
    }

    private static void dumpQueriesMap(PrintWriter pw, @Nullable Integer filteringId,
            SparseSetArray<Integer> queriesMap, String spacing,
            @Nullable ToString<Integer> toString) {
        for (int i = 0; i < queriesMap.size(); i++) {
            Integer callingId = queriesMap.keyAt(i);
            if (Objects.equals(callingId, filteringId)) {
                // don't filter target package names if the calling is filteringId
                dumpPackageSet(
                        pw, null /*filteringId*/, queriesMap.get(callingId),
                        toString == null
                                ? callingId.toString()
                                : toString.toString(callingId),
                        spacing, toString);
            } else {
                dumpPackageSet(
                        pw, filteringId, queriesMap.get(callingId),
                        toString == null
                                ? callingId.toString()
                                : toString.toString(callingId),
                        spacing, toString);
            }
        }
    }

    private interface ToString<T> {
        String toString(T input);
    }

    private static <T> void dumpPackageSet(PrintWriter pw, @Nullable T filteringId,
            Set<T> targetPkgSet, String subTitle, String spacing,
            @Nullable ToString<T> toString) {
        if (targetPkgSet != null && targetPkgSet.size() > 0
                && (filteringId == null || targetPkgSet.contains(filteringId))) {
            pw.append(spacing).append(subTitle).println(":");
            for (T item : targetPkgSet) {
                if (filteringId == null || Objects.equals(filteringId, item)) {
                    pw.append(spacing).append("  ")
                            .println(toString == null ? item : toString.toString(item));
                }
            }
        }
    }
}
