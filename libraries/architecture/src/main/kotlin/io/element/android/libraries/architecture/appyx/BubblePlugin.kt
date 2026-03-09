/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.architecture.appyx

import com.bumble.appyx.core.plugin.Plugin

/**
 * A marker plugin used to identify that a [com.bumble.appyx.core.node.Node] is running
 * inside a Bubble context.
 */
class BubblePlugin : Plugin
