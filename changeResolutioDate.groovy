import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.index.IssueIndexingService
import com.adaptavist.hapi.jira.issues.Issues
import java.sql.Timestamp

// --- Configuración ---
def dryRun = false  // true = solo loguea, no escribe

def issueManager = ComponentAccessor.issueManager
def changeHistoryManager = ComponentAccessor.changeHistoryManager
def ofBiz = ComponentAccessor.getOfBizDelegator()
def indexing = ComponentAccessor.getComponent(IssueIndexingService)

// --- Tu JQL ---
def jql = """
((project = BAU AND issuetype = Query) OR (project = REQ AND issuetype = Request))
AND statusCategory = Done
AND resolutiondate IS EMPTY
"""

def total = 0
def updated = 0

Issues.search(jql).each { hapiIssue ->

    total++
    def issueKey = hapiIssue.key.toString()

    try {
        def result = changeResolutionDate(issueKey, issueManager, changeHistoryManager, ofBiz, indexing, dryRun)
        if(result) updated++
    } catch(Exception e){
        log.error("❌ Error procesando ${issueKey}: ${e.message}", e)
    }
}

log.warn("🔹 Total issues evaluados: ${total}")
log.warn("🔹 Total issues actualizados: ${updated}")

// --- Función ---
def changeResolutionDate(issueKey, issueManager, changeHistoryManager, ofBiz, indexing, dryRun){

    def issue = issueManager.getIssueObject(issueKey)
    if(!issue){
        log.warn("❌ ${issueKey}: issue no encontrado")
        return false
    }

    // 1. Obtener cambio de STATUS → RESOLVED
    def statusChanges = changeHistoryManager.getChangeItemsForField(issue, "status")
    def resolvedChange = statusChanges.reverse().find { it.toString == "Resolved" }

    if(!resolvedChange){
        log.warn("⚠️ ${issueKey}: no tiene cambio de status a Resolved")
        return false
    }

    def resolvedTimestamp = new Timestamp(resolvedChange.created.time)
    log.warn("📌 ${issueKey}: fecha REAL cambio a Resolved → ${resolvedTimestamp}")

    // 2. Validar que resolution exista
    if(!issue.getResolution()){
        log.warn("⚠️ ${issueKey}: resolution vacío. No se puede actualizar resolutiondate")
        return false
    }

    // 3. Escribir DIRECTO en BD vía OfBiz
    if(!dryRun){
        def gv = issue.getGenericValue()
        gv.set("resolutiondate", resolvedTimestamp)
        ofBiz.store(gv)

        // 4. Reindexar
        indexing.reIndex(issue)

        // 5. Verificación
        def reloaded = issueManager.getIssueObject(issueKey)
        log.warn("✅ ${issueKey}: resolutiondate actualizado → ${reloaded.getResolutionDate()}")
    } else {
        log.warn("💡 DRY-RUN: ${issueKey}: se habría actualizado a ${resolvedTimestamp}")
    }

    return true
}
