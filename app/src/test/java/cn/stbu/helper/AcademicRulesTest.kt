package cn.stbu.helper

import cn.stbu.helper.backend.academic.AcademicParsers
import cn.stbu.helper.backend.auth.PortalPolicy
import cn.stbu.helper.backend.homework.HomeworkReminderPlan
import cn.stbu.helper.backend.homework.ReminderTask

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** User-owned validation cases; the agent does not execute tests in this project. */
class AcademicRulesTest {
    @Test fun oauthAndHttpCallbacksUseSeparateNavigationPolicy() {
        assertEquals(PortalPolicy.Action.INTERNAL, PortalPolicy.classify("https://open.weixin.qq.com/connect/qrconnect"))
        assertEquals(PortalPolicy.Action.INTERNAL, PortalPolicy.classify("https://graph.qq.com/oauth2.0/show"))
        assertEquals(PortalPolicy.Action.UPGRADE, PortalPolicy.classify("http://i.chaoxing.com/base?ws=1"))
        assertEquals(PortalPolicy.Action.BLOCK, PortalPolicy.classify("https://i.chaoxing.com.attacker.example/base"))
        assertFalse(PortalPolicy.dataUrl("https://graph.qq.com/oauth2.0/show"))
    }
    @Test fun httpUpgradePreservesEncodedOauthQuery() {
        val query = "?redirect_uri=https%3A%2F%2Fi.chaoxing.com%2Fbase%3Fws%3D1%26a%3D2"
        assertEquals("https://passport2.chaoxing.com/oauth2/weixin/login$query", PortalPolicy.https("http://passport2.chaoxing.com/oauth2/weixin/login$query"))
    }
    @Test fun scheduleRetainsRealWeekAndSectionMetadata() {
        val rows = JSONArray().put(JSONObject().put("lessonId", "12").put("name", "数学").put("dayOfWeek", 3).put("beginNumber", 2).put("length", 2).put("teacherName", "教师").put("location", "A201"))
        val record = AcademicParsers.lessons(rows, 7).single()
        assertEquals(7, record.getJSONArray("weeks").getInt(0))
        assertEquals(listOf(2, 3), (0 until record.getJSONArray("sections").length()).map { record.getJSONArray("sections").getInt(it) })
    }
    @Test fun remindersDoNotTreatUnfinishedAsFinishedOrRepeatSameWindow() {
        val now = 1_800_000_000_000L
        val task = ReminderTask("w1", "作业", "未完成", now + 2 * 60 * 60 * 1000)
        val first = HomeworkReminderPlan.plan(listOf(task), emptySet(), now).single()
        assertEquals("3 小时内截止", first.reason)
        assertTrue(HomeworkReminderPlan.plan(listOf(task), setOf(first.key), now + 60000).isEmpty())
        assertTrue(HomeworkReminderPlan.plan(listOf(task.copy(status = "待批阅")), emptySet(), now).isEmpty())
        assertTrue(HomeworkReminderPlan.plan(listOf(task.copy(deadlineAt = now - 1)), emptySet(), now).isEmpty())
    }
    @Test fun deadlineReadsTheLabelledRangeNotDatesInsideQuestions() {
        val now = Instant.parse("2026-12-28T00:00:00Z").toEpochMilli()
        val html = "<div>作答时间：12-28 10:00 至 01-02 23:59</div><p>题目：2000-01-01 00:00 发生了什么？</p>"
        assertEquals(Instant.parse("2027-01-02T15:59:00Z").toEpochMilli(), AcademicParsers.detailDeadline(html, now))
        assertEquals(0, AcademicParsers.deadline("剩余 2 天", now))
    }
    @Test fun gradeAndExamFieldsUseSchoolJson() {
        val rows = JSONArray().put(JSONObject().put("kcmc", "数学").put("cj", "85").put("xf", "4").put("kch_id", "M01").put("xnm", "2026").put("xqm", "3"))
        assertEquals("85", AcademicParsers.schoolRows(rows, "grades").getJSONObject(0).getString("score"))
        val exams = JSONArray().put(JSONObject().put("kcmc", "数学").put("kssj", "2026-12-1 09:00").put("cdmc", "A201"))
        assertEquals("2026-12-01", AcademicParsers.schoolRows(exams, "exams").getJSONObject(0).getString("date"))
    }
}
