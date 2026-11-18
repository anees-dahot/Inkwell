package app.inkwell.ui

import androidx.compose.animation.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.inkwell.data.model.ContentType
import app.inkwell.ui.components.LocalAnimScope
import app.inkwell.ui.components.LocalContainer
import app.inkwell.ui.components.LocalSharedScope
import app.inkwell.ui.motion.LocalReducedMotion
import app.inkwell.ui.motion.Motion
import app.inkwell.ui.screens.DetailsScreen
import app.inkwell.ui.screens.NovelReaderScreen
import app.inkwell.ui.screens.PlayerScreen
import app.inkwell.ui.screens.ReaderScreen
import kotlinx.serialization.Serializable

@Serializable object MainRoute
@Serializable data class DetailsRoute(val sourceId: String, val entryId: String, val title: String, val coverUrl: String? = null)
@Serializable data class ReaderRoute(val sourceId: String, val entryId: String, val episodeId: String)
@Serializable data class NovelRoute(val sourceId: String, val entryId: String, val episodeId: String)
@Serializable data class PlayerRoute(val sourceId: String, val entryId: String, val episodeId: String)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNav() {
    val nav = rememberNavController()
    val container = LocalContainer.current
    val reduced = LocalReducedMotion.current
    val fade = Motion.Default.specFor<Float>(reduced)
    val slideSpec = Motion.Sheet.specFor<IntOffset>(reduced)

    fun typeOf(sourceId: String) = container.registry.find(sourceId)?.type ?: ContentType.MANGA
    fun openEpisode(sourceId: String, entryId: String, episodeId: String) {
        when (typeOf(sourceId)) {
            ContentType.VIDEO -> nav.navigate(PlayerRoute(sourceId, entryId, episodeId))
            ContentType.NOVEL -> nav.navigate(NovelRoute(sourceId, entryId, episodeId))
            else -> nav.navigate(ReaderRoute(sourceId, entryId, episodeId))
        }
    }

    SharedTransitionLayout {
        NavHost(
            nav, startDestination = MainRoute,
            enterTransition = { fadeIn(fade) },
            exitTransition = { fadeOut(fade) },
            popEnterTransition = { fadeIn(fade) },
            popExitTransition = { fadeOut(fade) },
        ) {
            composable<MainRoute> {
                CompositionLocalProvider(LocalSharedScope provides this@SharedTransitionLayout, LocalAnimScope provides this) {
                    MainShell(
                        onOpenEntry = { nav.navigate(DetailsRoute(it.sourceId, it.id, it.title, it.coverUrl)) },
                        onOpenUpdate = { openEpisode(it.sourceId, it.entryId, it.episodeId) },
                        onOpenHistory = { openEpisode(it.sourceId, it.entryId, it.episodeId) },
                    )
                }
            }
            composable<DetailsRoute> { e ->
                val r = e.toRoute<DetailsRoute>()
                CompositionLocalProvider(LocalSharedScope provides this@SharedTransitionLayout, LocalAnimScope provides this) {
                    DetailsScreen(
                        r.sourceId, r.entryId, r.title, r.coverUrl,
                        onBack = { nav.popBackStack() },
                        onOpenEpisode = { entry, ep -> openEpisode(entry.sourceId, entry.id, ep.id) },
                    )
                }
            }
            composable<ReaderRoute>(
                enterTransition = { if (reduced) fadeIn(fade) else slideInVertically(slideSpec) { it } + fadeIn(fade) },
                popExitTransition = { if (reduced) fadeOut(fade) else slideOutVertically(slideSpec) { it } + fadeOut(fade) },
            ) { e ->
                val r = e.toRoute<ReaderRoute>()
                ReaderScreen(r.sourceId, r.entryId, r.episodeId, onBack = { nav.popBackStack() })
            }
            composable<NovelRoute>(
                enterTransition = { if (reduced) fadeIn(fade) else slideInVertically(slideSpec) { it } + fadeIn(fade) },
                popExitTransition = { if (reduced) fadeOut(fade) else slideOutVertically(slideSpec) { it } + fadeOut(fade) },
            ) { e ->
                val r = e.toRoute<NovelRoute>()
                NovelReaderScreen(r.sourceId, r.entryId, r.episodeId, onBack = { nav.popBackStack() })
            }
            composable<PlayerRoute>(
                enterTransition = { if (reduced) fadeIn(fade) else slideInVertically(slideSpec) { it } + fadeIn(fade) },
                popExitTransition = { if (reduced) fadeOut(fade) else slideOutVertically(slideSpec) { it } + fadeOut(fade) },
            ) { e ->
                val r = e.toRoute<PlayerRoute>()
                PlayerScreen(r.sourceId, r.entryId, r.episodeId, onBack = { nav.popBackStack() })
            }
        }
    }
}
