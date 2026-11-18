package app.inkwell.data.source

import app.inkwell.data.model.Source

class SourceRegistry(val sources: List<Source>) {
    fun get(id: String): Source = sources.first { it.id == id }
    fun find(id: String): Source? = sources.firstOrNull { it.id == id }
}
