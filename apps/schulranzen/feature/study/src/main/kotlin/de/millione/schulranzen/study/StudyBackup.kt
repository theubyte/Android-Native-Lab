package de.millione.schulranzen.study

import android.content.Context
import de.millione.schulranzen.study.data.StudyDatabase

/** Closes study.db before a restore replaces the database files. */
fun closeStudyDatabase(context: Context) {
    runCatching { StudyDatabase.get(context).close() }
}
