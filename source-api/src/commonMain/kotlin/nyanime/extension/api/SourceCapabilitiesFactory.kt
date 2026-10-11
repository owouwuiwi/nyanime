package nyanime.extension.api

/** Optional host capability. Reference it only from the separately declared optional factory,
 * never from a base source/factory that must load in another compatible host. */
interface SourceCapabilitiesFactory {
    fun attach(sources: List<Any>): List<Any>
}
