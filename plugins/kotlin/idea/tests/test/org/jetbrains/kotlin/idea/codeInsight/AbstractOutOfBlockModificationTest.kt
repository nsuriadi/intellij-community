// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.codeInsight

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiModificationTrackerImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import org.jetbrains.kotlin.idea.FrontendInternals
import org.jetbrains.kotlin.idea.caches.resolve.analyzeWithAllCompilerChecks
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.caches.trackers.KotlinPackageModificationListener
import org.jetbrains.kotlin.idea.caches.trackers.outOfBlockModificationCount
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager
import org.jetbrains.kotlin.idea.test.DirectiveBasedActionUtils
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.idea.test.InTextDirectivesUtils
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

abstract class AbstractOutOfBlockModificationTest : KotlinLightCodeInsightFixtureTestCase() {
    protected fun doTest(unused: String?) {
        val psiFile = myFixture.configureByFile(fileName())
        val ktFile = psiFile.safeAs<KtFile>()
        if (ktFile?.isScript() == true) {
            ScriptConfigurationManager.updateScriptDependenciesSynchronously(ktFile)
        }
        val expectedOutOfBlock = expectedOutOfBlockResult
        val text = psiFile.text
        val expectedPackageTrackerChange = InTextDirectivesUtils.isDirectiveDefined(text, PACKAGE_CHANGE_DIRECTIVE)
        val isErrorChecksDisabled = InTextDirectivesUtils.isDirectiveDefined(text, DISABLE_ERROR_CHECKS_DIRECTIVE)
        val isSkipCheckDefined = InTextDirectivesUtils.isDirectiveDefined(text, SKIP_ANALYZE_CHECK_DIRECTIVE)
        val project = myFixture.project
        val tracker =
            PsiManager.getInstance(project).modificationTracker as PsiModificationTrackerImpl
        val element = psiFile.findElementAt(myFixture.caretOffset)
        assertNotNull("Should be valid element", element)
        val packageTracker = KotlinPackageModificationListener.getInstance(project).packageTracker
        val ptcBeforeType = packageTracker.modificationCount
        val oobBeforeType = ktFile?.outOfBlockModificationCount
        val modificationCountBeforeType = tracker.modificationCount

        // have to analyze file before any change to support incremental analysis
        ktFile?.analyzeWithAllCompilerChecks()

        myFixture.type(stringToType)
        PsiDocumentManager.getInstance(project).commitDocument(myFixture.getDocument(myFixture.file))
        val oobAfterCount = ktFile?.outOfBlockModificationCount
        val ptcAfterType = packageTracker.modificationCount
        val modificationCountAfterType = tracker.modificationCount
        assertTrue(
            "Modification tracker should always be changed after type",
            modificationCountBeforeType != modificationCountAfterType
        )

        assertEquals(
            "Result for out of block test differs from expected on element in file:\n"
                    + FileUtil.loadFile(testDataFile()),
            expectedOutOfBlock, oobBeforeType != oobAfterCount
        )
        assertEquals(
            "package modification tracker differs from expected:\n"
                    + FileUtil.loadFile(testDataFile()),
            expectedPackageTrackerChange, ptcBeforeType != ptcAfterType
        )
        ktFile?.let {
            if (!isErrorChecksDisabled) {
                checkForUnexpectedErrors(it)
            }
            DirectiveBasedActionUtils.inspectionChecks(name, it)

            if (!isSkipCheckDefined && !isErrorChecksDisabled) {
                checkOOBWithDescriptorsResolve(expectedOutOfBlock)
            }
        }
    }

    private fun checkForUnexpectedErrors(ktFile: KtFile) {
        val diagnosticsProvider: (KtFile) -> Diagnostics = { it.analyzeWithAllCompilerChecks().bindingContext.diagnostics }
        DirectiveBasedActionUtils.checkForUnexpectedWarnings(ktFile, diagnosticsProvider = diagnosticsProvider)
        DirectiveBasedActionUtils.checkForUnexpectedErrors(ktFile, diagnosticsProvider = diagnosticsProvider)
    }

    private fun checkOOBWithDescriptorsResolve(expectedOutOfBlock: Boolean) {
        ApplicationManager.getApplication().runWriteAction {
            (PsiManager.getInstance(myFixture.project).modificationTracker as PsiModificationTrackerImpl)
                .incOutOfCodeBlockModificationCounter()
        }
        val updateElement = myFixture.file.findElementAt(myFixture.caretOffset - 1)
        val kDoc: KDoc? = PsiTreeUtil.getParentOfType(updateElement, KDoc::class.java, false)
        val ktExpression: KtExpression? = PsiTreeUtil.getParentOfType(updateElement, KtExpression::class.java, false)
        val ktDeclaration: KtDeclaration? = PsiTreeUtil.getParentOfType(updateElement, KtDeclaration::class.java, false)
        val ktElement = ktExpression ?: ktDeclaration ?: return
        val facade = ktElement.containingKtFile.getResolutionFacade()

        @OptIn(FrontendInternals::class)
        val session = facade.getFrontendService(ResolveSession::class.java)

        session.forceResolveAll()
        val context = session.bindingContext
        if (ktExpression != null && ktExpression !== ktDeclaration) {
            val expression = if (ktExpression is KtFunctionLiteral) ktExpression.getParent() as KtLambdaExpression else ktExpression
            val processed = context.get(BindingContext.PROCESSED, expression)
            val expressionProcessed = processed === java.lang.Boolean.TRUE
            assertEquals(
                "Expected out-of-block should result expression analyzed and vise versa", expectedOutOfBlock,
                expressionProcessed
            )
        } else if (updateElement !is PsiComment && kDoc == null) { // comments could be ignored from analyze
            val declarationProcessed =
                context.get(
                    BindingContext.DECLARATION_TO_DESCRIPTOR,
                    ktDeclaration
                ) != null
            assertEquals(
                "Expected out-of-block should result declaration analyzed and vise versa", expectedOutOfBlock,
                declarationProcessed
            )
        }
    }

    private val stringToType: String
        get() = stringToType(myFixture)

    private val expectedOutOfBlockResult: Boolean
        get() {
            val text = myFixture.getDocument(myFixture.file).text
            val outOfCodeBlockDirective = InTextDirectivesUtils.findStringWithPrefixes(
                text,
                OUT_OF_CODE_BLOCK_DIRECTIVE
            )
            assertNotNull(
                "${fileName()}: Expectation of code block result test should be configured with " +
                        "\"// " + OUT_OF_CODE_BLOCK_DIRECTIVE + " TRUE\" or " +
                        "\"// " + OUT_OF_CODE_BLOCK_DIRECTIVE + " FALSE\" directive in the file",
                outOfCodeBlockDirective
            )
            return outOfCodeBlockDirective?.toBoolean() ?: false
        }

    companion object {
        const val OUT_OF_CODE_BLOCK_DIRECTIVE = "OUT_OF_CODE_BLOCK:"
        const val PACKAGE_CHANGE_DIRECTIVE = "PACKAGE_CHANGE"
        const val DISABLE_ERROR_CHECKS_DIRECTIVE = "DISABLE_ERROR_CHECKS"
        const val SKIP_ANALYZE_CHECK_DIRECTIVE = "SKIP_ANALYZE_CHECK"
        const val TYPE_DIRECTIVE = "TYPE:"

        fun stringToType(fixture: JavaCodeInsightTestFixture): String {
            val text = fixture.getDocument(fixture.file).text
            val typeDirectives =
                InTextDirectivesUtils.findStringWithPrefixes(text, TYPE_DIRECTIVE)
            return if (typeDirectives != null) StringUtil.unescapeStringCharacters(typeDirectives) else "a"
        }
    }
}