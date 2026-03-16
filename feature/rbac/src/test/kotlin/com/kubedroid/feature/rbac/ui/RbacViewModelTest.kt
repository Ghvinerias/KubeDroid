package com.kubedroid.feature.rbac.ui

import com.kubedroid.feature.rbac.domain.model.CanIResult
import com.kubedroid.feature.rbac.domain.model.RbacBinding
import com.kubedroid.feature.rbac.domain.model.RbacRole
import com.kubedroid.feature.rbac.domain.repository.RbacRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class RbacViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh maps dangerous role marker to ui flag`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val repository = FakeRbacRepository(
            namespacedRoles = listOf(
                RbacRole(
                    name = "reader",
                    namespace = "default",
                    rules = listOf("verbs=get | resources=pods", "DANGEROUS: wildcard resources (*)"),
                ),
            ),
        )

        val viewModel = RbacViewModel(repository)
        advanceUntilIdle()

        val role = viewModel.uiState.value.roles.first()
        assertEquals("reader", role.name)
        assertEquals(2, role.ruleCount)
        assertTrue(role.isDangerous)
    }

    @Test
    fun `check can i updates result and summary`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val repository = FakeRbacRepository(canIResult = CanIResult.Denied)
        val viewModel = RbacViewModel(repository)
        advanceUntilIdle()

        viewModel.onIntent(RbacIntent.UpdateCanIResource("pods"))
        viewModel.onIntent(RbacIntent.SelectCanIVerb("delete"))
        viewModel.onIntent(RbacIntent.SelectCanINamespace("kube-system"))
        viewModel.onIntent(RbacIntent.CheckCanI)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CanIResult.Denied, state.canIResult)
        assertEquals("delete", state.canICheckSummary?.verb)
        assertEquals("pods", state.canICheckSummary?.resource)
        assertEquals("kube-system", state.canICheckSummary?.namespace)
    }
}

private class FakeRbacRepository(
    private val namespacedRoles: List<RbacRole> = emptyList(),
    private val clusterRoles: List<RbacRole> = emptyList(),
    private val namespacedBindings: List<RbacBinding> = emptyList(),
    private val clusterBindings: List<RbacBinding> = emptyList(),
    private val canIResult: CanIResult = CanIResult.Unknown,
) : RbacRepository {

    override suspend fun listRoles(namespace: String): List<RbacRole> = namespacedRoles

    override suspend fun listClusterRoles(): List<RbacRole> = clusterRoles

    override suspend fun listRoleBindings(namespace: String): List<RbacBinding> = namespacedBindings

    override suspend fun listClusterRoleBindings(): List<RbacBinding> = clusterBindings

    override suspend fun canI(
        verb: String,
        resource: String,
        namespace: String?,
        subjectName: String,
    ): CanIResult = canIResult
}
