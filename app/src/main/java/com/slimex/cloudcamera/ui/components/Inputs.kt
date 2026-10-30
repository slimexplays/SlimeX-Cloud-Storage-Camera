package com.slimex.cloudcamera.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.slimex.cloudcamera.ui.theme.CharcoalElevated
import com.slimex.cloudcamera.ui.theme.ErrorRed
import com.slimex.cloudcamera.ui.theme.OutlineMedium
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import com.slimex.cloudcamera.ui.theme.TextTertiary

/**
 * Text field with the SlimeX look: charcoal fill, rounded 16dp, purple focus ring,
 * inline validation message and optional trailing action.
 */
@Composable
fun SlimeXTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    helper: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    singleLine: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = singleLine,
            label = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            placeholder = {
                if (placeholder.isNotBlank()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
            },
            isError = error != null,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
            ),
            trailingIcon = trailing,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                disabledTextColor = TextTertiary,
                cursorColor = SlimePurple,
                focusedBorderColor = SlimePurple,
                unfocusedBorderColor = OutlineMedium,
                errorBorderColor = ErrorRed,
                focusedContainerColor = CharcoalElevated,
                unfocusedContainerColor = CharcoalElevated,
                disabledContainerColor = CharcoalElevated.copy(alpha = 0.6f),
                errorContainerColor = CharcoalElevated,
                focusedLabelColor = SlimePurple,
                unfocusedLabelColor = TextSecondary,
                errorLabelColor = ErrorRed,
            ),
        )
        val supporting = error ?: helper
        if (!supporting.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = supporting,
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) ErrorRed else TextTertiary,
            )
        }
    }
}
