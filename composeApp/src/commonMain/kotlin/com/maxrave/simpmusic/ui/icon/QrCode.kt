package com.maxrave.simpmusic.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
val SimpIcons.QrCode: ImageVector
  get() {
    if (_QrCode != null) {
      return _QrCode!!
    }
    _QrCode =
      ImageVector.Builder(
          name = "QrCode",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 24f,
          viewportHeight = 24f,
        )
        .apply {
          path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.Companion.NonZero,
          ) {
            moveTo(3f, 10f)
            verticalLineTo(4f)
            quadTo(3f, 3.57f, 3.29f, 3.29f)
            reflectiveQuadTo(4f, 3f)
            horizontalLineToRelative(6f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(11f, 4f)
            verticalLineToRelative(6f)
            quadToRelative(0f, 0.42f, -0.29f, 0.71f)
            reflectiveQuadTo(10f, 11f)
            horizontalLineTo(4f)
            quadTo(3.58f, 11f, 3.29f, 10.71f)
            quadTo(3f, 10.43f, 3f, 10f)
            close()
            moveTo(5f, 9f)
            horizontalLineTo(9f)
            verticalLineTo(5f)
            horizontalLineTo(5f)
            verticalLineTo(9f)
            close()
            moveTo(3f, 20f)
            verticalLineTo(14f)
            quadTo(3f, 13.58f, 3.29f, 13.29f)
            reflectiveQuadTo(4f, 13f)
            horizontalLineToRelative(6f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(11f, 14f)
            verticalLineToRelative(6f)
            quadToRelative(0f, 0.43f, -0.29f, 0.71f)
            reflectiveQuadTo(10f, 21f)
            horizontalLineTo(4f)
            quadTo(3.58f, 21f, 3.29f, 20.71f)
            quadTo(3f, 20.43f, 3f, 20f)
            close()
            moveTo(5f, 19f)
            horizontalLineTo(9f)
            verticalLineTo(15f)
            horizontalLineTo(5f)
            verticalLineToRelative(4f)
            close()
            moveToRelative(8f, -9f)
            verticalLineTo(4f)
            quadTo(13f, 3.57f, 13.29f, 3.29f)
            reflectiveQuadTo(14f, 3f)
            horizontalLineToRelative(6f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(21f, 4f)
            verticalLineToRelative(6f)
            quadToRelative(0f, 0.42f, -0.29f, 0.71f)
            reflectiveQuadTo(20f, 11f)
            horizontalLineTo(14f)
            quadToRelative(-0.42f, 0f, -0.71f, -0.29f)
            quadTo(13f, 10.43f, 13f, 10f)
            close()
            moveTo(15f, 9f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineTo(15f)
            verticalLineTo(9f)
            close()
            moveToRelative(4f, 12f)
            verticalLineTo(19f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineTo(19f)
            close()
            moveTo(13f, 15f)
            verticalLineTo(13f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineTo(13f)
            close()
            moveToRelative(2f, 2f)
            verticalLineTo(15f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineTo(15f)
            close()
            moveToRelative(-2f, 2f)
            verticalLineTo(17f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineTo(13f)
            close()
            moveToRelative(2f, 2f)
            verticalLineTo(19f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineTo(15f)
            close()
            moveToRelative(2f, -2f)
            verticalLineTo(17f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineTo(17f)
            close()
            moveToRelative(0f, -4f)
            verticalLineTo(13f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineTo(17f)
            close()
            moveToRelative(2f, 2f)
            verticalLineTo(15f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineTo(19f)
            close()
          }
        }
        .buildSimpIcon()
    return _QrCode!!
  }

private var _QrCode: ImageVector? = null
