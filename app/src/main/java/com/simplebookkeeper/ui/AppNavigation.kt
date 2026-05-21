package com.simplebookkeeper.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.simplebookkeeper.R
import com.simplebookkeeper.ui.screens.*
import com.simplebookkeeper.viewmodel.MainViewModel

sealed class Screen(val route: String, val labelRes: Int, val icon: ImageVector, val iconSelected: ImageVector) {
    object Home : Screen("home", R.string.nav_book, Icons.Outlined.Book, Icons.Filled.Book)
    object Search : Screen("search", R.string.nav_search, Icons.Outlined.Search, Icons.Filled.Search)
    object Statistics : Screen("statistics", R.string.nav_statistics, Icons.Outlined.BarChart, Icons.Filled.BarChart)
    object Savings : Screen("savings", R.string.nav_savings, Icons.Outlined.Savings, Icons.Filled.Savings)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings)
}

val bottomNavItems = listOf(Screen.Home, Screen.Search, Screen.Statistics, Screen.Savings, Screen.Settings)

@Composable
fun AppNavigation(
    viewModel: MainViewModel,
    isTablet: Boolean,
    activity: FragmentActivity
) {
    val navController = rememberNavController()

    if (isTablet) {
        // 平板：左侧导航栏 + 右侧内容
        TabletLayout(viewModel = viewModel, navController = navController, activity = activity)
    } else {
        // 手机：底部导航栏
        PhoneLayout(viewModel = viewModel, navController = navController, activity = activity)
    }
}

@Composable
fun PhoneLayout(
    viewModel: MainViewModel,
    navController: androidx.navigation.NavHostController,
    activity: FragmentActivity
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = { Icon(if (selected) screen.iconSelected else screen.icon, contentDescription = stringResource(screen.labelRes)) },
                        label = { Text(stringResource(screen.labelRes)) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        MainNavHost(
            viewModel = viewModel,
            navController = navController,
            modifier = Modifier.padding(innerPadding),
            activity = activity
        )
    }
}

@Composable
fun TabletLayout(
    viewModel: MainViewModel,
    navController: androidx.navigation.NavHostController,
    activity: FragmentActivity
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail(
            modifier = Modifier.width(80.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            bottomNavItems.forEach { screen ->
                val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                NavigationRailItem(
                    icon = { Icon(if (selected) screen.iconSelected else screen.icon, contentDescription = stringResource(screen.labelRes)) },
                    label = { Text(stringResource(screen.labelRes)) },
                    selected = selected,
                    onClick = {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
        MainNavHost(
            viewModel = viewModel,
            navController = navController,
            modifier = Modifier.weight(1f),
            activity = activity
        )
    }
}

@Composable
fun MainNavHost(
    viewModel: MainViewModel,
    navController: androidx.navigation.NavHostController,
    modifier: Modifier = Modifier,
    activity: FragmentActivity
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = viewModel,
                onAddTransaction = { navController.navigate("add_transaction") },
                onTransactionClick = { id -> navController.navigate("edit_transaction/$id") }
            )
        }
        composable(Screen.Search.route) {
            SearchScreen(
                viewModel = viewModel,
                onTransactionClick = { id -> navController.navigate("edit_transaction/$id") }
            )
        }
        composable(Screen.Statistics.route) {
            StatisticsScreen(
                viewModel = viewModel,
                onTransactionClick = { id -> navController.navigate("edit_transaction/$id") }
            )
        }
        composable(Screen.Savings.route) {
            SavingsScreen(viewModel = viewModel)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(viewModel = viewModel)
        }
        composable("add_transaction") {
            AddEditTransactionScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("edit_transaction/{id}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.toLongOrNull()
            AddEditTransactionScreen(
                viewModel = viewModel,
                transactionId = id,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
