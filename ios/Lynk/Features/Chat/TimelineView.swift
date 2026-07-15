import SwiftUI

struct TimelineView: View {
    let chat: ChatStore
    let deviceID: String
    let bridge: BridgeClient

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 14) {
                    if chat.timeline.isEmpty {
                        ContentUnavailableView(
                            "Start a conversation",
                            systemImage: "bubble.left.and.text.bubble.right",
                            description: Text("Choose any model advertised by your Lynk bridge, then send a message.")
                        )
                        .padding(.top, 80)
                    }
                    ForEach(chat.timeline) { item in
                        TimelineRow(item: item, chat: chat, deviceID: deviceID, bridge: bridge)
                            .id(item.id)
                    }
                }
                .padding()
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: chat.timeline.count) {
                guard let last = chat.timeline.last else { return }
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last.id, anchor: .bottom) }
            }
        }
    }
}

private struct TimelineRow: View {
    let item: ChatTimelineItem
    let chat: ChatStore
    let deviceID: String
    let bridge: BridgeClient

    var body: some View {
        switch item.kind {
        case .message: MessageRow(item: item)
        case .reasoning: ReasoningRow(item: item)
        case .tool:
            if let tool = item.tool {
                ToolRow(
                    tool: tool,
                    toggle: { chat.toggleTool(tool.eventID) },
                    action: { chat.perform($0, deviceID: deviceID, bridge: bridge) }
                )
            }
        }
    }
}

private struct MessageRow: View {
    let item: ChatTimelineItem
    private var isUser: Bool { item.role == "user" }
    private var isSystem: Bool { item.role == "system" }

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 46) }
            VStack(alignment: .leading, spacing: 7) {
                if isSystem {
                    Label("System", systemImage: "exclamationmark.circle")
                        .font(.caption.bold())
                        .foregroundStyle(.secondary)
                }
                MarkdownText(item.text)
                ForEach(item.attachments) { attachment in
                    Label(attachment.displayName, systemImage: attachment.kind == "image" ? "photo" : "doc")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if item.isStreaming && item.text.isEmpty { ProgressView().controlSize(.small) }
            }
            .textSelection(.enabled)
            .padding(.horizontal, 13)
            .padding(.vertical, 10)
            .background(
                isUser ? Color.accentColor : isSystem ? Color(.tertiarySystemFill) : Color(.secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: 18)
            )
            .foregroundStyle(isUser ? Color.white : Color.primary)
            .contextMenu { Button("Copy") { UIPasteboard.general.string = item.text } }
            if !isUser { Spacer(minLength: 32) }
        }
        .frame(maxWidth: .infinity)
    }
}

private struct MarkdownText: View {
    let attributed: AttributedString

    init(_ markdown: String) {
        attributed = (try? AttributedString(markdown: markdown, options: .init(interpretedSyntax: .full))) ?? AttributedString(markdown)
    }

    var body: some View { Text(attributed).frame(maxWidth: .infinity, alignment: .leading) }
}

private struct ReasoningRow: View {
    let item: ChatTimelineItem
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Label("Reasoning", systemImage: "brain.head.profile")
                .font(.caption.bold())
            Text(item.text)
                .font(.callout)
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.leading, 10)
        .overlay(alignment: .leading) { Rectangle().fill(Color.accentColor.opacity(0.6)).frame(width: 3) }
    }
}

private struct ToolRow: View {
    let tool: ChatToolEvent
    let toggle: () -> Void
    let action: (ChatToolAction) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: toggle) {
                HStack {
                    Image(systemName: symbol)
                        .foregroundStyle(color)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tool.title).font(.subheadline.bold())
                        if let summary = tool.summary { Text(summary).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                    }
                    Spacer()
                    if tool.status == "running" { ProgressView().controlSize(.small) }
                    Image(systemName: tool.isExpanded ? "chevron.up" : "chevron.down").font(.caption)
                }
            }
            .buttonStyle(.plain)
            if tool.isExpanded {
                if let args = tool.args { DetailBlock(title: "Input", text: args.displayText) }
                if let output = tool.output { DetailBlock(title: "Output", text: output.displayText) }
                if let error = tool.error { DetailBlock(title: "Error", text: error) }
            }
            if !tool.actions.isEmpty {
                HStack {
                    ForEach(tool.actions) { item in
                        if item.style == "primary" {
                            Button(item.label) { action(item) }
                                .buttonStyle(.borderedProminent)
                        } else {
                            Button(item.label) { action(item) }
                                .buttonStyle(.bordered)
                                .tint(item.style == "danger" ? .red : .accentColor)
                        }
                    }
                }
            }
        }
        .padding(12)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
    }

    private var symbol: String {
        switch tool.status {
        case "completed": "checkmark.circle.fill"
        case "failed": "xmark.circle.fill"
        case "blocked": "hand.raised.circle.fill"
        default: "wrench.and.screwdriver"
        }
    }
    private var color: Color { tool.status == "failed" ? .red : tool.status == "blocked" ? .orange : .accentColor }
}

private struct DetailBlock: View {
    let title: String
    let text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.caption.bold()).foregroundStyle(.secondary)
            Text(text).font(.caption.monospaced()).textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(8)
        .background(Color(.tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 8))
    }
}
