// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.core

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor.Kind.*
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.resolve.getLanguageVersionSettings
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCodeFragment
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.OverridingUtil
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtension
import org.jetbrains.kotlin.resolve.findOriginalTopMostOverriddenDescriptors
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.utils.getImplicitReceiversHierarchy
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor
import java.util.*

fun DeclarationDescriptorWithVisibility.isVisible(from: DeclarationDescriptor, languageVersionSettings: LanguageVersionSettings): Boolean {
    return isVisible(from, null, languageVersionSettings = languageVersionSettings)
}

fun DeclarationDescriptorWithVisibility.isVisible(
    context: PsiElement,
    receiverExpression: KtExpression?,
    bindingContext: BindingContext,
    resolutionFacade: ResolutionFacade
): Boolean {
    val resolutionScope = context.getResolutionScope(bindingContext, resolutionFacade)
    val from = resolutionScope.ownerDescriptor
    return isVisible(from, receiverExpression, bindingContext, resolutionScope, resolutionFacade.getLanguageVersionSettings())
}

private fun DeclarationDescriptorWithVisibility.isVisible(
    from: DeclarationDescriptor,
    receiverExpression: KtExpression?,
    bindingContext: BindingContext? = null,
    resolutionScope: LexicalScope? = null,
    languageVersionSettings: LanguageVersionSettings
): Boolean {
    if (DescriptorVisibilityUtils.isVisibleWithAnyReceiver(this, from, languageVersionSettings)) return true

    if (bindingContext == null || resolutionScope == null) return false

    // for extension it makes no sense to check explicit receiver because we need dispatch receiver which is implicit in this case
    if (receiverExpression != null && !isExtension) {
        val receiverType = bindingContext.getType(receiverExpression) ?: return false
        val explicitReceiver = ExpressionReceiver.create(receiverExpression, receiverType, bindingContext)
        return DescriptorVisibilityUtils.isVisible(explicitReceiver, this, from, languageVersionSettings)
    } else {
        return resolutionScope.getImplicitReceiversHierarchy().any {
            DescriptorVisibilityUtils.isVisible(it.value, this, from, languageVersionSettings)
        }
    }
}

private fun compareDescriptorsText(project: Project, d1: DeclarationDescriptor, d2: DeclarationDescriptor): Boolean {
    if (d1 == d2) return true
    if (d1.name != d2.name) return false

    val renderedD1 = IdeDescriptorRenderers.SOURCE_CODE.render(d1)
    val renderedD2 = IdeDescriptorRenderers.SOURCE_CODE.render(d2)
    if (renderedD1 == renderedD2) return true

    val declarations1 = DescriptorToSourceUtilsIde.getAllDeclarations(project, d1)
    val declarations2 = DescriptorToSourceUtilsIde.getAllDeclarations(project, d2)
    if (declarations1 == declarations2 && declarations1.isNotEmpty()) return true

    return false
}

fun compareDescriptors(project: Project, currentDescriptor: DeclarationDescriptor?, originalDescriptor: DeclarationDescriptor?): Boolean {
    if (currentDescriptor == originalDescriptor) return true
    if (currentDescriptor == null || originalDescriptor == null) return false

    if (currentDescriptor.name != originalDescriptor.name) return false

    if (originalDescriptor is SyntheticJavaPropertyDescriptor && currentDescriptor is SyntheticJavaPropertyDescriptor) {
        return compareDescriptors(project, currentDescriptor.getMethod, originalDescriptor.getMethod)
    }

    if (compareDescriptorsText(project, currentDescriptor, originalDescriptor)) return true

    if (originalDescriptor is CallableDescriptor && currentDescriptor is CallableDescriptor) {
        val overriddenOriginalDescriptor = originalDescriptor.findOriginalTopMostOverriddenDescriptors()
        val overriddenCurrentDescriptor = currentDescriptor.findOriginalTopMostOverriddenDescriptors()

        if (overriddenOriginalDescriptor.size != overriddenCurrentDescriptor.size) return false
        return overriddenCurrentDescriptor.zip(overriddenOriginalDescriptor).all {
            compareDescriptorsText(project, it.first, it.second)
        }
    }

    return false
}

fun DescriptorVisibility.toKeywordToken(): KtModifierKeywordToken = when (val normalized = normalize()) {
    DescriptorVisibilities.PUBLIC -> KtTokens.PUBLIC_KEYWORD
    DescriptorVisibilities.PROTECTED -> KtTokens.PROTECTED_KEYWORD
    DescriptorVisibilities.INTERNAL -> KtTokens.INTERNAL_KEYWORD
    else -> {
        if (DescriptorVisibilities.isPrivate(normalized)) {
            KtTokens.PRIVATE_KEYWORD
        } else {
            error("Unexpected visibility '$normalized'")
        }
    }
}

fun <D : CallableMemberDescriptor> D.getDirectlyOverriddenDeclarations(): Collection<D> {
    val result = LinkedHashSet<D>()
    for (overriddenDescriptor in overriddenDescriptors) {
        @Suppress("UNCHECKED_CAST")
        when (overriddenDescriptor.kind) {
            DECLARATION -> result.add(overriddenDescriptor as D)
            FAKE_OVERRIDE, DELEGATION -> result.addAll((overriddenDescriptor as D).getDirectlyOverriddenDeclarations())
            SYNTHESIZED -> {
                //do nothing
            }
            else -> throw AssertionError("Unexpected callable kind ${overriddenDescriptor.kind}: $overriddenDescriptor")
        }
    }
    return OverridingUtil.filterOutOverridden(result)
}

fun <D : CallableMemberDescriptor> D.getDeepestSuperDeclarations(withThis: Boolean = true): Collection<D> {
    val overriddenDeclarations = DescriptorUtils.getAllOverriddenDeclarations(this)
    if (overriddenDeclarations.isEmpty() && withThis) {
        return setOf(this)
    }

    return overriddenDeclarations.filterNot(DescriptorUtils::isOverride)
}

fun <T : DeclarationDescriptor> T.unwrapIfFakeOverride(): T {
    return if (this is CallableMemberDescriptor) DescriptorUtils.unwrapFakeOverride(this) else this
}
