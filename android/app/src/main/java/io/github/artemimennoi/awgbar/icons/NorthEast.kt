/*
 * Copyright 2025 The Android Open Source Project
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

// Copied from androidx.compose.material:material-icons-extended 1.7.8 so the app does not ship all its icons.
package io.github.artemimennoi.awgbar.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Rounded.NorthEast: ImageVector
    get() {
        if (_northEast != null) {
            return _northEast!!
        }
        _northEast = materialIcon(name = "Rounded.NorthEast") {
            materialPath {
                moveTo(9.0f, 6.0f)
                lineTo(9.0f, 6.0f)
                curveToRelative(0.0f, 0.56f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(5.59f)
                lineTo(4.7f, 17.89f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
                horizontalLineToRelative(0.0f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                lineTo(17.0f, 8.41f)
                verticalLineTo(14.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineTo(18.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineTo(6.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(10.0f)
                curveTo(9.45f, 5.0f, 9.0f, 5.45f, 9.0f, 6.0f)
                close()
            }
        }
        return _northEast!!
    }

private var _northEast: ImageVector? = null
