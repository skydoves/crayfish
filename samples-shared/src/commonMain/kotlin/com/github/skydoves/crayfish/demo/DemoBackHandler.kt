/*
 * Designed and developed by 2026 skydoves (Jaewoong Eum)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.skydoves.crayfish.demo

import androidx.compose.runtime.Composable

/**
 * Sends the platform's own back gesture to [onBack] while [enabled].
 *
 * Compose Multiplatform 1.12 has no common back handler, so this is four small actuals rather than
 * one call. Only Android has a system back to intercept in this demo; the rest are no-ops, and say
 * so rather than pretending the hook is wired.
 */
@Composable
internal expect fun DemoBackHandler(enabled: Boolean, onBack: () -> Unit)
