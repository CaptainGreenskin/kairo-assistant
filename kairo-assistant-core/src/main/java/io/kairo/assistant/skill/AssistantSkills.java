package io.kairo.assistant.skill;

import io.kairo.api.skill.SkillCategory;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.skill.DefaultSkillRegistry;
import java.util.List;

public final class AssistantSkills {

    private AssistantSkills() {}

    public static SkillRegistry createRegistry() {
        DefaultSkillRegistry registry = new DefaultSkillRegistry();
        registerBuiltinSkills(registry);
        return registry;
    }

    public static void registerBuiltinSkills(SkillRegistry registry) {
        registry.register(dailyBriefing());
        registry.register(webResearch());
        registry.register(summarizeUrl());
        registry.register(translate());
        registry.register(emailDraft());
        registry.register(meetingNotes());
        registry.register(dataAnalysis());
        registry.register(codeReview());
        registry.register(journaling());
        registry.register(travelPlanning());
    }

    static SkillDefinition dailyBriefing() {
        return new SkillDefinition(
                "daily-briefing",
                "1.0",
                "Generate a personalized daily briefing with weather, calendar, todos, and news summary",
                """
                You are creating a daily briefing for the user. Follow these steps:

                1. Use the `time` tool to get the current date and time
                2. Use the `weather` tool to get today's weather forecast
                3. Use the `calendar` tool to list today's events
                4. Use the `todo` tool with action=list to show pending tasks
                5. Use the `memory_search` tool to find any recent important notes

                Format the briefing as a clean, scannable summary with sections:
                - 🌤 Weather
                - 📅 Today's Schedule
                - ✅ Pending Tasks
                - 📝 Recent Notes

                Keep it concise — no more than 300 words total.
                """,
                List.of("/briefing", "/daily", "daily briefing", "morning briefing"),
                SkillCategory.GENERAL);
    }

    static SkillDefinition webResearch() {
        return new SkillDefinition(
                "web-research",
                "1.0",
                "Perform multi-step web research on a topic with source synthesis",
                """
                You are performing web research for the user. Follow these steps:

                1. Break the research question into 2-3 specific search queries
                2. Use the `web_fetch` tool to retrieve content from relevant URLs
                3. Cross-reference information from multiple sources
                4. Synthesize findings into a structured report

                Output format:
                - **Summary**: 2-3 sentence overview
                - **Key Findings**: Bulleted list of important facts
                - **Sources**: List of URLs consulted
                - **Confidence**: High/Medium/Low based on source agreement

                Always cite which source each finding comes from.
                """,
                List.of("/research", "research this", "look up", "find out about"),
                SkillCategory.GENERAL);
    }

    static SkillDefinition summarizeUrl() {
        return new SkillDefinition(
                "summarize-url",
                "1.0",
                "Fetch and summarize the content of a URL",
                """
                You are summarizing web content for the user.

                1. Use the `web_fetch` tool to retrieve the URL content
                2. Identify the main topic, key points, and conclusions
                3. Create a structured summary

                Output format:
                - **Title**: The page title
                - **TL;DR**: One sentence summary
                - **Key Points**: 3-5 bullet points
                - **Notable Quotes**: Any standout passages (if applicable)

                Keep the summary under 200 words.
                """,
                List.of("/summarize", "summarize this url", "tldr"),
                SkillCategory.GENERAL);
    }

    static SkillDefinition translate() {
        return new SkillDefinition(
                "translate",
                "1.0",
                "Translate text between languages with context awareness",
                """
                You are a translation assistant. When translating:

                1. Detect the source language if not specified
                2. Translate the text naturally, not word-by-word
                3. Preserve the tone and register of the original
                4. For technical terms, provide both the translation and original in parentheses
                5. If there are cultural nuances, add a brief note

                Output format:
                - **Source** (detected language): original text
                - **Translation**: translated text
                - **Notes**: Any cultural or contextual notes (if applicable)

                Default target language is English if translating from another language,
                or Chinese if translating from English.
                """,
                List.of("/translate", "translate this", "翻译"),
                SkillCategory.GENERAL);
    }

    static SkillDefinition emailDraft() {
        return new SkillDefinition(
                "email-draft",
                "1.0",
                "Draft a professional email based on context and intent",
                """
                You are drafting an email for the user.

                1. Ask for or infer: recipient, subject, key points, tone (formal/casual)
                2. Use the `user_profile` tool to get the user's name for the signature
                3. Draft the email with proper structure

                Output format:
                ```
                Subject: [subject line]

                [Greeting],

                [Body paragraphs]

                [Closing],
                [User's name]
                ```

                Default tone is professional-but-warm. Keep emails concise.
                Use the `clipboard` tool to copy the result if the user wants it.
                """,
                List.of("/email", "draft email", "write email", "compose email"),
                SkillCategory.GENERAL);
    }

    static SkillDefinition meetingNotes() {
        return new SkillDefinition(
                "meeting-notes",
                "1.0",
                "Structure and organize meeting notes with action items",
                """
                You are organizing meeting notes for the user.

                1. Parse the raw notes or transcript provided
                2. Identify attendees, decisions, and action items
                3. Structure the notes

                Output format:
                - **Meeting**: [title/topic]
                - **Date**: Use `time` tool for current date
                - **Attendees**: [list]
                - **Key Discussion Points**: Numbered list
                - **Decisions Made**: Bulleted list
                - **Action Items**: Checklist with owners and deadlines
                - **Next Steps**: What happens next

                Use the `note` tool to save the structured notes.
                Use the `calendar` tool to add any follow-up meetings.
                """,
                List.of("/meeting", "meeting notes", "organize notes"),
                SkillCategory.GENERAL);
    }

    static SkillDefinition dataAnalysis() {
        return new SkillDefinition(
                "data-analysis",
                "1.0",
                "Analyze data from files (CSV, JSON) and provide insights",
                """
                You are analyzing data for the user.

                1. Use `read_file` to load the data file
                2. Use `code_execute` with Python to parse and analyze:
                   - Basic statistics (count, mean, median, std)
                   - Distribution of key fields
                   - Trends or patterns
                   - Outliers or anomalies
                3. Summarize findings

                Output format:
                - **Dataset Overview**: Rows, columns, types
                - **Key Statistics**: Important numbers
                - **Insights**: What the data tells us
                - **Recommendations**: Suggested actions based on data

                For visualization, generate matplotlib charts via code_execute.
                """,
                List.of("/analyze", "analyze data", "analyze this file", "data analysis"),
                SkillCategory.DATA);
    }

    static SkillDefinition codeReview() {
        return new SkillDefinition(
                "code-review",
                "1.0",
                "Review code files for bugs, style, and improvement opportunities",
                """
                You are performing a code review.

                1. Use `read_file` to read the target file(s)
                2. Analyze for:
                   - Bugs and potential runtime errors
                   - Security vulnerabilities (OWASP Top 10)
                   - Performance issues
                   - Code style and readability
                   - Missing error handling
                   - Test coverage gaps
                3. Provide actionable feedback

                Output format:
                - **Summary**: Overall assessment (1-2 sentences)
                - **Critical Issues**: Must-fix problems
                - **Improvements**: Nice-to-have changes
                - **Positive Observations**: What's done well

                Severity levels: 🔴 Critical, 🟡 Warning, 🟢 Suggestion
                """,
                List.of("/review", "review code", "code review"),
                SkillCategory.CODE);
    }

    static SkillDefinition journaling() {
        return new SkillDefinition(
                "journaling",
                "1.0",
                "Interactive journaling assistant with prompts and reflection",
                """
                You are a journaling assistant helping the user reflect on their day.

                1. Use `time` tool to get today's date
                2. Offer journaling prompts or accept free-form input
                3. Ask follow-up questions to deepen reflection
                4. Save the entry using the `note` tool

                Journaling prompts to cycle through:
                - What went well today?
                - What challenged you?
                - What did you learn?
                - What are you grateful for?
                - What would you do differently?

                Save format: "Journal [YYYY-MM-DD]: [key themes]"
                Tag notes with "journal" for easy retrieval.
                """,
                List.of("/journal", "journal", "journaling", "reflect"),
                SkillCategory.GENERAL);
    }

    static SkillDefinition travelPlanning() {
        return new SkillDefinition(
                "travel-planning",
                "1.0",
                "Help plan trips with itinerary, packing lists, and local tips",
                """
                You are a travel planning assistant.

                1. Gather trip details: destination, dates, budget, interests
                2. Use `weather` tool for weather forecast at destination
                3. Use `web_fetch` for destination research
                4. Create a structured travel plan

                Output format:
                - **Trip Overview**: Destination, dates, budget
                - **Day-by-Day Itinerary**: Activities and timing
                - **Packing List**: Based on weather and activities
                - **Local Tips**: Cultural notes, food recommendations
                - **Budget Breakdown**: Estimated costs

                Use `calendar` to add trip events.
                Use `note` to save the itinerary.
                """,
                List.of("/travel", "plan trip", "travel planning"),
                SkillCategory.GENERAL);
    }
}
