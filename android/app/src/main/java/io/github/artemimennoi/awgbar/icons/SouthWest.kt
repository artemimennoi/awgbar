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

public val Icons.Rounded.SouthWest: ImageVector
    get() {
        if (_southWest != null) {
            return _southWest!!
        }
        _southWest = materialIcon(name = "Rounded.SouthWest") {
            materialPath {
                moveTo(15.0f, 18.0f)
                lineTo(15.0f, 18.0f)
                curveToRelative(0.0f, -0.56f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(8.41f)
                lineTo(19.3f, 6.11f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
                lineToRelative(0.0f, 0.0f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
                lineTo(7.0f, 15.59f)
                verticalLineTo(10.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(6.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineTo(18.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineTo(14.0f)
                curveTo(14.55f, 19.0f, 15.0f, 18.55f, 15.0f, 18.0f)
                close()
            }
        }
        return _southWest!!
    }

private var _southWest: ImageVector? = null
