package com.kingzcheung.xime.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun SettingsScreen(
    initialRoute: String? = null,
    initialPluginId: String? = null,
    onThemeChanged: () -> Unit = {},
    onWizardBack: () -> Unit = {}
) {
    val navController = rememberNavController()
    val startDestination = if (initialRoute == "manage_dict") SettingsRoutes.Dictionary
    else if (initialRoute == "schema") SettingsRoutes.Schema
    else if (initialRoute == "plugins") SettingsRoutes.Plugins
    else SettingsRoutes.Main

    LaunchedEffect(initialPluginId) {
        if (initialPluginId != null) {
            navController.navigate("plugin_market_detail/$initialPluginId")
        }
    }
    
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(SettingsRoutes.Main) {
            SettingsMainContent(
                onNavigateToSchema = { navController.navigate(SettingsRoutes.Schema) },
                onNavigateToMarket = { navController.navigate(SettingsRoutes.Market) },
                onNavigateToTheme = { navController.navigate(SettingsRoutes.Theme) },
                onNavigateToKeyEffect = { navController.navigate(SettingsRoutes.KeyEffect) },
                onNavigateToLayoutDisplay = { navController.navigate(SettingsRoutes.LayoutDisplay) },
                onNavigateToDictionary = { navController.navigate(SettingsRoutes.Dictionary) },
                onNavigateToPlugins = { navController.navigate(SettingsRoutes.Plugins) },
                onNavigateToModelLocal = { navController.navigate(SettingsRoutes.ModelLocal) },
                onNavigateToSmartPrediction = { navController.navigate(SettingsRoutes.SmartPrediction) },
                onNavigateToSpeechToText = { navController.navigate(SettingsRoutes.SpeechToText) },
                onNavigateToAbout = { navController.navigate(SettingsRoutes.About) },
                onNavigateToClipboardSync = { navController.navigate(SettingsRoutes.ClipboardSync) },
                onNavigateToBackup = { navController.navigate(SettingsRoutes.Backup) }
            )
        }
        composable(SettingsRoutes.Schema) {
            SchemaSettingsContent(
                onBack = {
                    if (initialRoute == "schema") {
                        navController.navigate(SettingsRoutes.Main) {
                            popUpTo(SettingsRoutes.Main) { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                    onWizardBack()
                },
                onNavigateToMarket = { navController.navigate(SettingsRoutes.Market) },
                onNavigateToRimeFileBrowser = { navController.navigate(SettingsRoutes.RimeFileBrowser) },
            )
        }
        composable(SettingsRoutes.Market) {
            MarketHubContent(
                onBack = { navController.popBackStack() },
                onNavigateToDetail = { schemeId ->
                    navController.navigate("schema_market_detail/$schemeId")
                },
                onNavigateToModelDetail = { modelId ->
                    navController.navigate("model_market_detail/$modelId")
                },
                onNavigateToPluginDetail = { pluginId ->
                    navController.navigate("plugin_market_detail/$pluginId")
                },
                onNavigateToLayoutDetail = { layoutId ->
                    navController.navigate("layout_market_detail/$layoutId")
                },
                onNavigateToLocal = { navController.navigate(SettingsRoutes.SchemaLocal) },
                onNavigateToModelLocal = { navController.navigate(SettingsRoutes.ModelLocal) },
            )
        }
        composable(SettingsRoutes.SchemaLocal) {
            SchemaLocalContent(
                onBack = { navController.popBackStack() },
            )
        }
        composable(SettingsRoutes.ModelLocal) {
            ModelLocalContent(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = SettingsRoutes.SchemaMarketDetail,
            arguments = listOf(navArgument("schemeId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val schemeId = backStackEntry.arguments?.getString("schemeId") ?: return@composable
            MarketSchemeDetailContent(
                schemeId = schemeId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = SettingsRoutes.ModelMarketDetail,
            arguments = listOf(navArgument("modelId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments?.getString("modelId") ?: return@composable
            MarketModelDetailContent(
                modelId = modelId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = SettingsRoutes.PluginMarketDetail,
            arguments = listOf(navArgument("pluginId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val pluginId = backStackEntry.arguments?.getString("pluginId") ?: return@composable
            PluginMarketDetailContent(
                pluginId = pluginId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = SettingsRoutes.LayoutMarketDetail,
            arguments = listOf(navArgument("layoutId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val layoutId = backStackEntry.arguments?.getString("layoutId") ?: return@composable
            LayoutMarketDetailContent(
                layoutId = layoutId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(SettingsRoutes.Theme) {
            ThemeSettingsContent(
                onBack = { navController.popBackStack() },
                onThemeChanged = onThemeChanged
            )
        }
        composable(SettingsRoutes.Plugins) {
            PluginsSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToPluginSettings = { pluginId ->
                    navController.navigate("${SettingsRoutes.PluginSettings}/$pluginId")
                },
                onNavigateToPluginMarketDetail = { pluginId ->
                    navController.navigate("plugin_market_detail/$pluginId")
                },
                onNavigateToSpeechToText = { navController.navigate(SettingsRoutes.SpeechToText) },
                onNavigateToClipboardSync = { navController.navigate(SettingsRoutes.ClipboardSync) },
                onNavigateToBackup = { navController.navigate(SettingsRoutes.Backup) }
            )
        }
        composable(
            route = "${SettingsRoutes.PluginSettings}/{pluginId}",
            arguments = listOf(navArgument("pluginId") { type = NavType.StringType })
        ) { backStackEntry ->
            val pluginId = backStackEntry.arguments?.getString("pluginId")
            PluginSettingsContent(
                pluginId = pluginId ?: "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.KeyEffect) {
            KeyEffectSettingsContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.LayoutDisplay) {
            LayoutDisplaySettingsContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.SmartPrediction) {
            SmartPredictionSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToModelManagement = { navController.navigate(SettingsRoutes.ModelLocal) },
                onNavigateToModelDetail = { modelId ->
                    navController.navigate("model_market_detail/$modelId")
                }
            )
        }
        composable(SettingsRoutes.SpeechToText) {
            SpeechToTextSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToPluginSettings = { pluginId ->
                    navController.navigate("${SettingsRoutes.PluginSettings}/$pluginId")
                },
                onNavigateToPlugins = { navController.navigate(SettingsRoutes.Plugins) }
            )
        }
        composable(SettingsRoutes.Dictionary) {
            DictionarySettingsContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.SchemaDictBrowser) {
            SchemaDictBrowserContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.RimeFileBrowser) {
            RimeFileBrowserContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.ClipboardSync) {
            ClipboardSyncSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToPlugins = { navController.navigate(SettingsRoutes.Plugins) },
                onNavigateToMarket = { navController.navigate(SettingsRoutes.MarketPlugins) }
            )
        }
        composable(SettingsRoutes.Backup) {
            BackupSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToPlugins = { navController.navigate(SettingsRoutes.Plugins) },
                onNavigateToMarket = { navController.navigate(SettingsRoutes.MarketPlugins) }
            )
        }
        composable(SettingsRoutes.About) {
            AboutContent(
                onBack = { navController.popBackStack() },
                onNavigateToPrivacy = { navController.navigate(SettingsRoutes.Privacy) },
                onNavigateToLicenses = { navController.navigate(SettingsRoutes.Licenses) },
                onNavigateToLogViewer = { navController.navigate(SettingsRoutes.LogViewer) },
                onNavigateToDeveloper = { navController.navigate(SettingsRoutes.Developer) },
                onNavigateToStorageSpace = { navController.navigate(SettingsRoutes.StorageSpace) }
            )
        }
        composable(SettingsRoutes.Developer) {
            DeveloperContent(
                onBack = { navController.popBackStack() },
                onNavigateToHandwritingCapture = { navController.navigate(SettingsRoutes.HandwritingCapture) }
            )
        }
        composable(SettingsRoutes.StorageSpace) {
            StorageSpaceScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPlugins = { navController.navigate(SettingsRoutes.Plugins) }
            )
        }
        composable(SettingsRoutes.MarketModel) {
            MarketHubContent(
                onBack = { navController.popBackStack() },
                onNavigateToDetail = { schemeId ->
                    navController.navigate("schema_market_detail/$schemeId")
                },
                onNavigateToModelDetail = { modelId ->
                    navController.navigate("model_market_detail/$modelId")
                },
                onNavigateToPluginDetail = { pluginId ->
                    navController.navigate("plugin_market_detail/$pluginId")
                },
                onNavigateToLayoutDetail = { layoutId ->
                    navController.navigate("layout_market_detail/$layoutId")
                },
                onNavigateToLocal = { navController.navigate(SettingsRoutes.SchemaLocal) },
                onNavigateToModelLocal = { navController.navigate(SettingsRoutes.ModelLocal) },
                initialTab = 1,
            )
        }
        composable(SettingsRoutes.MarketPlugins) {
            MarketHubContent(
                onBack = { navController.popBackStack() },
                onNavigateToDetail = { schemeId ->
                    navController.navigate("schema_market_detail/$schemeId")
                },
                onNavigateToModelDetail = { modelId ->
                    navController.navigate("model_market_detail/$modelId")
                },
                onNavigateToPluginDetail = { pluginId ->
                    navController.navigate("plugin_market_detail/$pluginId")
                },
                onNavigateToLayoutDetail = { layoutId ->
                    navController.navigate("layout_market_detail/$layoutId")
                },
                onNavigateToLocal = { navController.navigate(SettingsRoutes.SchemaLocal) },
                onNavigateToModelLocal = { navController.navigate(SettingsRoutes.ModelLocal) },
                initialTab = 2,
            )
        }
        composable(SettingsRoutes.LogViewer) {
            LogViewerScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.HandwritingCapture) {
            HandwritingCaptureScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.Privacy) {
            PrivacyPolicyContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.Licenses) {
            LicensesContent(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
