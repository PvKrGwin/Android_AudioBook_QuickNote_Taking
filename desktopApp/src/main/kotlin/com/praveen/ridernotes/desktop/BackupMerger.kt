package com.praveen.ridernotes.desktop

import java.io.File

/** Conservative merge: retain every independently identified note and Topic point. */
object BackupMerger {
    fun keepBoth(
        base: BackupSnapshot,
        local: BackupSnapshot,
        cloud: BackupSnapshot
    ): BackupSnapshot {
        copyLocalOnlyRecordings(local, cloud)
        copyLocalOnlyAssets(local, cloud)

        val groups = mergeById(base.groups, local.groups, cloud.groups) { it.id }
        val items = mergeById(base.items, local.items, cloud.items) { it.id }
        val placements = mergeById(base.placements, local.placements, cloud.placements) { it.id }
            .groupBy { it.groupId }
            .values
            .flatMap { values ->
                values.sortedBy { it.order }.mapIndexed { index, value -> value.copy(order = index) }
            }
        return BackupEditor.saveLibrary(
            snapshot = cloud,
            groups = groups,
            items = items,
            placements = placements,
            legend = cloud.colorLegend + local.colorLegend
        )
    }

    private fun copyLocalOnlyRecordings(local: BackupSnapshot, cloud: BackupSnapshot) {
        val cloudIds = cloud.notes.mapTo(mutableSetOf()) { it.id }
        local.notes.filter { it.id !in cloudIds }.forEach { note ->
            local.extractedRoot.walkTopDown()
                .filter { it.isFile && it.nameWithoutExtension == note.id }
                .forEach { source ->
                    val target = File(cloud.extractedRoot, "recordings/${source.name}")
                    target.parentFile?.mkdirs()
                    if (!target.exists()) source.copyTo(target)
                }
        }
    }

    private fun copyLocalOnlyAssets(local: BackupSnapshot, cloud: BackupSnapshot) {
        val localAssets = File(local.libraryRoot, "assets")
        if (!localAssets.exists()) return
        localAssets.walkTopDown().filter(File::isFile).forEach { source ->
            val relative = source.relativeTo(local.libraryRoot).invariantSeparatorsPath
            val target = File(cloud.libraryRoot, relative)
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                source.copyTo(target)
            }
        }
    }

    private fun <T> mergeById(
        base: List<T>,
        local: List<T>,
        cloud: List<T>,
        id: (T) -> String
    ): List<T> {
        val baseMap = base.associateBy(id)
        val localMap = local.associateBy(id)
        val cloudMap = cloud.associateBy(id)
        return (localMap.keys + cloudMap.keys).mapNotNull { key ->
            val original = baseMap[key]
            val localValue = localMap[key]
            val cloudValue = cloudMap[key]
            when {
                localValue == null -> cloudValue // Safe keep-both: do not propagate deletion.
                cloudValue == null -> localValue
                localValue == cloudValue -> localValue
                localValue == original -> cloudValue
                cloudValue == original -> localValue
                else -> localValue // Same entity edited twice: local preference, never delete cloud-only IDs.
            }
        }
    }
}
