/*
 * Copyright 2026 Bitubi Solutions
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 */
package ao.bitubi.inference4j.server.ui;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** What the templates read. Plain data: the controller builds it, the templates only print it. */
public final class ViewModels {
    private ViewModels() {}

    /** {@code state} is downloading, loading, ready or failed (lower case, as in /healthz). */
    public record ModelStatus(String state, String name, String detail, long done, long total, String info) {
        public boolean ready() {
            return "ready".equals(state);
        }

        public boolean failed() {
            return "failed".equals(state);
        }

        public boolean downloading() {
            return "downloading".equals(state) && total > 0;
        }
    }

    public record SidebarItem(UUID id, String title, boolean current) {}

    public record SidebarGroup(String label, List<SidebarItem> items) {}

    /**
     * One message. For the assistant {@code html} is rendered Markdown and {@code footer} the tokens line; for the user
     * {@code text} is the raw text. {@code status} is null, stopped, interrupted or pending; {@code retryText} is the
     * user text a Retry button posts again.
     */
    public record MessageModel(String role, String html, String text, String footer, String status, String retryText) {
        public boolean user() {
            return "user".equals(role);
        }

        public boolean interrupted() {
            return "interrupted".equals(status) || "pending".equals(status);
        }
    }

    public record RunningModel(UUID turnId, String htmlSoFar) {}

    public record ContextModel(int used, int total) {
        public int percent() {
            return total <= 0 ? 0 : (int) Math.min(100, Math.round(used * 100.0 / total));
        }

        public boolean warn() {
            return total > 0 && used * 100L >= total * 85L;
        }

        public String label() {
            NumberFormat f = NumberFormat.getIntegerInstance(Locale.ROOT);
            return f.format(used) + " / " + f.format(total) + " tokens";
        }
    }

    public record SettingsModel(float temperature, int maxNewTokens) {}

    public record ChatModel(
        UUID id,
        String title,
        List<MessageModel> messages,
        RunningModel running,
        ContextModel context,
        SettingsModel settings,
        boolean readOnly,
        String readOnlyReason,
        String banner,
        List<String> starters
    ) {
        public boolean empty() {
            return messages.isEmpty() && running == null;
        }
    }

    public record PageModel(
        ModelStatus model,
        List<SidebarGroup> sidebar,
        String query,
        ChatModel chat,
        boolean historyWritable,
        String historyError
    ) {
        public String pageTitle() {
            String t = chat == null || chat.title() == null || chat.title().isBlank() ? "New chat" : chat.title();
            return t + " - bi4j";
        }
    }

    /** The footer line under an answer: {@code 212 tokens, 14.1 tok/s, stopped: end of text}. */
    public static String footer(String finishReason, Integer generatedTokens, Double tokensPerSecond, String status) {
        StringBuilder sb = new StringBuilder();
        if (generatedTokens != null) sb.append(generatedTokens).append(" tokens");
        if (tokensPerSecond != null && tokensPerSecond > 0) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(String.format(Locale.ROOT, "%.1f tok/s", tokensPerSecond));
        }
        String why = switch (finishReason == null ? "" : finishReason) {
            case "STOP" -> "end of text";
            case "LENGTH" -> "reached max tokens";
            case "STOP_SEQUENCE" -> "stop sequence";
            case "CANCELLED" -> "stopped";
            default -> "";
        };
        if ("stopped".equals(status) && why.isEmpty()) why = "stopped";
        if (!why.isEmpty()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(why);
        }
        return sb.toString();
    }
}
