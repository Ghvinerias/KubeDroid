package com.kubedroid.feature.rbac.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kubedroid.feature.rbac.domain.model.CanIResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RbacScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_RbacScreen_rolesTab_rendersRoleInfo() {
        setScreen(
            state = defaultState(
                selectedTab = RbacTab.Roles,
                roles = listOf(
                    RoleListItemUiModel(
                        name = "reader",
                        namespace = "default",
                        ruleCount = 2,
                        isDangerous = true,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("reader").assertIsDisplayed()
        composeRule.onNodeWithText("Namespace: default").assertIsDisplayed()
        composeRule.onNodeWithText("Rules: 2").assertIsDisplayed()
        composeRule.onNodeWithText("Dangerous").assertIsDisplayed()
    }

    @Test
    fun test_RbacScreen_bindingsTab_rendersBindingInfo() {
        setScreen(
            state = defaultState(
                selectedTab = RbacTab.Bindings,
                bindings = listOf(
                    BindingListItemUiModel(
                        name = "view-binding",
                        namespace = "default",
                        roleRefName = "view",
                        subjectCount = 3,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("view-binding").assertIsDisplayed()
        composeRule.onNodeWithText("RoleRef: view").assertIsDisplayed()
        composeRule.onNodeWithText("Subjects: 3").assertIsDisplayed()
        composeRule.onNodeWithText("Namespace: default").assertIsDisplayed()
    }

    @Test
    fun test_RbacScreen_tabSwitch_showsCanIChecker() {
        var screenState by mutableStateOf(defaultState(selectedTab = RbacTab.Roles))
        val intents = mutableListOf<RbacIntent>()
        composeRule.setContent {
            MaterialTheme {
                RbacScreen(
                    state = screenState,
                    onIntent = { intent ->
                        intents += intent
                        if (intent is RbacIntent.SelectTab) {
                            screenState = screenState.copy(selectedTab = intent.tab)
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("Can-I checker").performClick()
        composeRule.onNodeWithText("Resource").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(listOf(RbacIntent.SelectTab(RbacTab.CanI)), intents)
        }
    }

    @Test
    fun test_RbacScreen_canICheckButton_disabledWhenResourceBlank() {
        setScreen(state = defaultState(selectedTab = RbacTab.CanI, canIResourceInput = ""))

        composeRule.onNodeWithText("Check access").assertIsNotEnabled()
    }

    @Test
    fun test_RbacScreen_canICheckButton_enabledWhenResourceEntered() {
        setScreen(state = defaultState(selectedTab = RbacTab.CanI, canIResourceInput = "pods"))
        composeRule.onNodeWithText("Check access").assertIsEnabled()
    }

    @Test
    fun test_RbacScreen_checkAccessClick_dispatchesCheckCanI() {
        val intents = mutableListOf<RbacIntent>()
        setScreen(
            state = defaultState(selectedTab = RbacTab.CanI, canIResourceInput = "pods"),
            onIntent = { intents += it },
        )

        composeRule.onNodeWithText("Check access").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(RbacIntent.CheckCanI), intents)
        }
    }

    @Test
    fun test_RbacScreen_selectVerb_dispatchesSelectCanIVerb() {
        val intents = mutableListOf<RbacIntent>()
        setScreen(
            state = defaultState(
                selectedTab = RbacTab.CanI,
                canIResourceInput = "pods",
                canIVerb = "get",
            ),
            onIntent = { intents += it },
        )

        composeRule.onNodeWithText("get").performClick()
        composeRule.onNodeWithText("delete").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(RbacIntent.SelectCanIVerb("delete")), intents)
        }
    }

    @Test
    fun test_RbacScreen_selectScopeCluster_dispatchesSelectCanINamespaceNull() {
        val intents = mutableListOf<RbacIntent>()
        setScreen(
            state = defaultState(
                selectedTab = RbacTab.CanI,
                canIResourceInput = "pods",
                canINamespace = "default",
                namespaceOptions = listOf("default", "kube-system"),
            ),
            onIntent = { intents += it },
        )

        composeRule.onNodeWithText("default").performClick()
        composeRule.onNodeWithText("Cluster").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(RbacIntent.SelectCanINamespace(null)), intents)
        }
    }

    @Test
    fun test_RbacScreen_canIResultAndSummary_showResultAndExplanation() {
        setScreen(
            state = defaultState(
                selectedTab = RbacTab.CanI,
                canIResourceInput = "pods",
                canIVerb = "get",
                canINamespace = "default",
                canIResult = CanIResult.Allowed,
                canICheckSummary = CanICheckSummary(
                    verb = "get",
                    resource = "pods",
                    namespace = "default",
                ),
            ),
        )

        composeRule.onAllNodesWithText("Allowed").assertCountEquals(1)
        composeRule.onAllNodesWithText("The current identity can get pods in default.").assertCountEquals(1)
    }

    private fun setScreen(
        state: RbacUiState,
        onIntent: (RbacIntent) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                RbacScreen(
                    state = state,
                    onIntent = onIntent,
                )
            }
        }
    }

    private fun defaultState(
        selectedTab: RbacTab = RbacTab.Roles,
        namespaceOptions: List<String> = listOf("default", "kube-system"),
        roles: List<RoleListItemUiModel> = emptyList(),
        bindings: List<BindingListItemUiModel> = emptyList(),
        canIResourceInput: String = "",
        canIVerb: String = "get",
        canINamespace: String? = "default",
        canIResult: CanIResult? = null,
        canICheckSummary: CanICheckSummary? = null,
    ): RbacUiState {
        return RbacUiState(
            selectedTab = selectedTab,
            selectedNamespace = "default",
            namespaceOptions = namespaceOptions,
            roles = roles,
            bindings = bindings,
            canIResourceInput = canIResourceInput,
            canIVerb = canIVerb,
            canINamespace = canINamespace,
            canIResult = canIResult,
            canICheckSummary = canICheckSummary,
        )
    }
}
