package com.github.pderakhshanfar.codecocoonplugin.java

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.transformation.Transformation


/**
 * Marker interface for Java-specific transformations.
 */

interface JavaTransformation : Transformation {
    override fun accepts(context: FileContext): Boolean = context.language == Language.JAVA
}
