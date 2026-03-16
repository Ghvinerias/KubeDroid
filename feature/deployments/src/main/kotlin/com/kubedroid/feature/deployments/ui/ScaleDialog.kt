package com.kubedroid.feature.deployments.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.kubedroid.feature.deployments.R

@Composable
fun ScaleDialog(
    replicas: Int,
    onDismissRequest: () -> Unit,
    onDecreaseClick: () -> Unit,
    onIncreaseClick: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(id = R.string.deployment_scale_dialog_title))
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = onDecreaseClick) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = stringResource(
                            id = R.string.deployment_scale_dialog_decrease_cd,
                        ),
                    )
                }
                Text(
                    modifier = Modifier.width(
                        dimensionResource(id = R.dimen.deployment_scale_dialog_value_width),
                    ),
                    text = replicas.toString(),
                )
                IconButton(onClick = onIncreaseClick) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(
                            id = R.string.deployment_scale_dialog_increase_cd,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirmClick) {
                Text(text = stringResource(id = R.string.deployment_scale_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(id = R.string.deployment_scale_dialog_cancel))
            }
        },
    )
}
