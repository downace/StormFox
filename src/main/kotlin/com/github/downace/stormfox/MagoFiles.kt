package com.github.downace.stormfox

import com.intellij.psi.PsiFile

/** Recognize templates without depending on the optional Twig or TextMate language plugins. */
internal fun isTwigFile(file: PsiFile): Boolean = file.name.endsWith(".twig", ignoreCase = true)
