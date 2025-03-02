// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING
import com.intellij.codeInspection.ProblemHighlightType.INFORMATION
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.getLastLambdaExpression
import org.jetbrains.kotlin.idea.inspections.AssociateFunction.*
import org.jetbrains.kotlin.idea.intentions.callExpression
import org.jetbrains.kotlin.idea.project.languageVersionSettings
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

class ReplaceAssociateFunctionInspection : AbstractKotlinInspection() {
    companion object {
        private val associateFunctionNames = listOf("associate", "associateTo")
        private val associateFqNames = listOf(FqName("kotlin.collections.associate"), FqName("kotlin.sequences.associate"))
        private val associateToFqNames = listOf(FqName("kotlin.collections.associateTo"), FqName("kotlin.sequences.associateTo"))

        fun getAssociateFunctionAndProblemHighlightType(
            dotQualifiedExpression: KtDotQualifiedExpression,
            context: BindingContext = dotQualifiedExpression.analyze(BodyResolveMode.PARTIAL)
        ): Pair<AssociateFunction, ProblemHighlightType>? {
            val callExpression = dotQualifiedExpression.callExpression ?: return null
            val lambda = callExpression.lambda() ?: return null
            if (lambda.valueParameters.size > 1) return null
            val functionLiteral = lambda.functionLiteral
            if (functionLiteral.anyDescendantOfType<KtReturnExpression> { it.labelQualifier != null }) return null
            val lastStatement = functionLiteral.lastStatement() ?: return null
            val (keySelector, valueTransform) = lastStatement.pair(context) ?: return null
            val lambdaParameter = context[BindingContext.FUNCTION, functionLiteral]?.valueParameters?.singleOrNull() ?: return null
            return when {
                keySelector.isReferenceTo(lambdaParameter, context) -> {
                    val receiver =
                        dotQualifiedExpression.receiverExpression.getResolvedCall(context)?.resultingDescriptor?.returnType ?: return null
                    if ((KotlinBuiltIns.isArray(receiver) || KotlinBuiltIns.isPrimitiveArray(receiver)) &&
                        dotQualifiedExpression.languageVersionSettings.languageVersion < LanguageVersion.KOTLIN_1_4
                    ) return null
                    ASSOCIATE_WITH to GENERIC_ERROR_OR_WARNING
                }
                valueTransform.isReferenceTo(lambdaParameter, context) ->
                    ASSOCIATE_BY to GENERIC_ERROR_OR_WARNING
                else -> {
                    if (functionLiteral.bodyExpression?.statements?.size != 1) return null
                    ASSOCIATE_BY_KEY_AND_VALUE to INFORMATION
                }
            }
        }

        private fun KtExpression.isReferenceTo(descriptor: ValueParameterDescriptor, context: BindingContext): Boolean {
            return (this as? KtNameReferenceExpression)?.getResolvedCall(context)?.resultingDescriptor == descriptor
        }
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = dotQualifiedExpressionVisitor(fun(dotQualifiedExpression) {
        if (dotQualifiedExpression.languageVersionSettings.languageVersion < LanguageVersion.KOTLIN_1_3) return
        val callExpression = dotQualifiedExpression.callExpression ?: return
        val calleeExpression = callExpression.calleeExpression ?: return
        if (calleeExpression.text !in associateFunctionNames) return

        val context = dotQualifiedExpression.analyze(BodyResolveMode.PARTIAL)
        val fqName = callExpression.getResolvedCall(context)?.resultingDescriptor?.fqNameSafe ?: return
        val isAssociate = fqName in associateFqNames
        val isAssociateTo = fqName in associateToFqNames
        if (!isAssociate && !isAssociateTo) return

        val (associateFunction, highlightType) = getAssociateFunctionAndProblemHighlightType(dotQualifiedExpression, context) ?: return
        holder.registerProblemWithoutOfflineInformation(
            calleeExpression,
            KotlinBundle.message("replace.0.with.1", calleeExpression.text, associateFunction.name(isAssociateTo)),
            isOnTheFly,
            highlightType,
            ReplaceAssociateFunctionFix(associateFunction, isAssociateTo)
        )
    })
}

class ReplaceAssociateFunctionFix(private val function: AssociateFunction, private val hasDestination: Boolean) : LocalQuickFix {
    private val functionName = function.name(hasDestination)

    override fun getName() = KotlinBundle.message("replace.with.0", functionName)

    override fun getFamilyName() = name

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val dotQualifiedExpression = descriptor.psiElement.getStrictParentOfType<KtDotQualifiedExpression>() ?: return
        val receiverExpression = dotQualifiedExpression.receiverExpression
        val callExpression = dotQualifiedExpression.callExpression ?: return
        val lambda = callExpression.lambda() ?: return
        val lastStatement = lambda.functionLiteral.lastStatement() ?: return
        val (keySelector, valueTransform) = lastStatement.pair() ?: return

        val psiFactory = KtPsiFactory(dotQualifiedExpression)
        if (function == ASSOCIATE_BY_KEY_AND_VALUE) {
            val destination = if (hasDestination) {
                callExpression.valueArguments.firstOrNull()?.getArgumentExpression() ?: return
            } else {
                null
            }
            val newExpression = psiFactory.buildExpression {
                appendExpression(receiverExpression)
                appendFixedText(".")
                appendFixedText(functionName)
                appendFixedText("(")
                if (destination != null) {
                    appendExpression(destination)
                    appendFixedText(",")
                }
                appendLambda(lambda, keySelector)
                appendFixedText(",")
                appendLambda(lambda, valueTransform)
                appendFixedText(")")
            }
            dotQualifiedExpression.replace(newExpression)
        } else {
            lastStatement.replace(if (function == ASSOCIATE_WITH) valueTransform else keySelector)
            val newExpression = psiFactory.buildExpression {
                appendExpression(receiverExpression)
                appendFixedText(".")
                appendFixedText(functionName)
                val valueArgumentList = callExpression.valueArgumentList
                if (valueArgumentList != null) {
                    appendValueArgumentList(valueArgumentList)
                }
                if (callExpression.lambdaArguments.isNotEmpty()) {
                    appendLambda(lambda)
                }
            }
            dotQualifiedExpression.replace(newExpression)
        }
    }

    private fun BuilderByPattern<KtExpression>.appendLambda(lambda: KtLambdaExpression, body: KtExpression? = null) {
        appendFixedText("{")
        lambda.valueParameters.firstOrNull()?.nameAsName?.also {
            appendName(it)
            appendFixedText("->")
        }

        if (body != null) {
            appendExpression(body)
        } else {
            lambda.bodyExpression?.allChildren?.let(this::appendChildRange)
        }

        appendFixedText("}")
    }

    private fun BuilderByPattern<KtExpression>.appendValueArgumentList(valueArgumentList: KtValueArgumentList) {
        appendFixedText("(")
        valueArgumentList.arguments.forEachIndexed { index, argument ->
            if (index > 0) appendFixedText(",")
            appendExpression(argument.getArgumentExpression())
        }
        appendFixedText(")")
    }

    companion object {
        fun replaceLastStatementForAssociateFunction(callExpression: KtCallExpression, function: AssociateFunction) {
            val lastStatement = callExpression.lambda()?.functionLiteral?.lastStatement() ?: return
            val (keySelector, valueTransform) = lastStatement.pair() ?: return
            lastStatement.replace(if (function == ASSOCIATE_WITH) valueTransform else keySelector)
        }
    }
}

enum class AssociateFunction(val functionName: String) {
    ASSOCIATE_WITH("associateWith"), ASSOCIATE_BY("associateBy"), ASSOCIATE_BY_KEY_AND_VALUE("associateBy");

    fun name(hasDestination: Boolean): String {
        return if (hasDestination) "${functionName}To" else functionName
    }
}

private fun KtCallExpression.lambda(): KtLambdaExpression? {
    return lambdaArguments.singleOrNull()?.getArgumentExpression() as? KtLambdaExpression ?: getLastLambdaExpression()
}

private fun KtFunctionLiteral.lastStatement(): KtExpression? {
    return bodyExpression?.statements?.lastOrNull()
}

private fun KtExpression.pair(context: BindingContext = analyze(BodyResolveMode.PARTIAL)): Pair<KtExpression, KtExpression>? {
    return when (this) {
        is KtBinaryExpression -> {
            if (operationReference.text != "to") return null
            val left = left ?: return null
            val right = right ?: return null
            left to right
        }
        is KtCallExpression -> {
            if (calleeExpression?.text != "Pair") return null
            if (valueArguments.size != 2) return null
            if (getResolvedCall(context)?.resultingDescriptor?.containingDeclaration?.fqNameSafe != FqName("kotlin.Pair")) return null
            val first = valueArguments[0]?.getArgumentExpression() ?: return null
            val second = valueArguments[1]?.getArgumentExpression() ?: return null
            first to second
        }
        else -> return null
    }
}