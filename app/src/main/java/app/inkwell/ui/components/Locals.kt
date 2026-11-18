package app.inkwell.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.inkwell.AppContainer
import app.inkwell.ui.motion.Motion

val LocalContainer = compositionLocalOf<AppContainer> { error("no container") }

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Emits the tab index when the already-active bottom tab is tapped again (scroll to top). */
val LocalTabReselect = compositionLocalOf<kotlinx.coroutines.flow.SharedFlow<Int>> { kotlinx.coroutines.flow.MutableSharedFlow() }

/** Switches the bottom tab (0 Library, 1 Updates, 2 Browse, 3 History, 4 More). */
val LocalSwitchTab = compositionLocalOf<(Int) -> Unit> { {} }

@Composable
fun OnTabReselect(index: Int, action: suspend () -> Unit) {
    val flow = LocalTabReselect.current
    val act by androidx.compose.runtime.rememberUpdatedState(action)
    androidx.compose.runtime.LaunchedEffect(flow) { flow.collect { if (it == index) act() } }
}

/** Extra bottom padding for content that sits under the glass tab bar. */
val LocalBottomInset = compositionLocalOf { PaddingValues(0.dp) }

/** Cover shared-element transition (grid -> details), no-op outside a transition scope. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCover(key: String): Modifier {
    val sts = LocalSharedScope.current
    val avs = LocalAnimScope.current
    return if (sts != null && avs != null) {
        with(sts) { this@sharedCover.sharedElement(rememberSharedContentState(key = key), animatedVisibilityScope = avs, boundsTransform = { _, _ -> Motion.Default.spec() }) }
    } else this
}

@Composable
inline fun <reified VM : ViewModel> inkViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(c) } })
}
