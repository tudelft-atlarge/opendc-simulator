/*
 * MIT License
 *
 * Copyright (c) 2017 atlarge-research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.atlarge.opendc.model.odc.integration.jpa.converter

import com.atlarge.opendc.model.odc.platform.scheduler.Scheduler
import com.atlarge.opendc.model.odc.platform.scheduler.StageScheduler
import com.atlarge.opendc.model.odc.platform.scheduler.stages.machine.*
import com.atlarge.opendc.model.odc.platform.scheduler.stages.task.*
import javax.persistence.AttributeConverter

/**
 * An internal [AttributeConverter] that maps a name of a scheduler to the actual scheduler implementation.
 *
 * @author Fabian Mastenbroek (f.s.mastenbroek@student.tudelft.nl)
 */
class SchedulerConverter : AttributeConverter<Scheduler<*>, String> {
    /**
     * Converts the data stored in the database column into the
     * value to be stored in the entity attribute.
     * Note that it is the responsibility of the converter writer to
     * specify the correct dbData type for the corresponding column
     * for use by the JDBC driver: i.e., persistence providers are
     * not expected to do such type conversion.
     *
     * @param dbData the data from the database column to be converted
     * @return the converted value to be stored in the entity attribute
     */
    override fun convertToEntityAttribute(dbData: String?): Scheduler<*> =
        dbData?.let { convert(it.toUpperCase()) } ?: throw IllegalArgumentException("The scheduler $dbData does not exist")

    /**
     * Convert a name of a scheduler into a [StageScheduler] based on the pattern `SORTING_POLICY-SELECTION_POLICY`.
     */
    private fun convert(name: String): StageScheduler? {
        var parts = name.split("-")
        if (parts.size < 2) {
            // Some policies only use a single name, in that case we'll use the
            // entire name for both the sorting and selection policy.
            parts = listOf(parts[0], parts[0])
        }

        val (sorting, selection) = parts
        val sortingPolicy = when (sorting) {
            "FIFO" -> FifoSortingPolicy()
            "SRTF" -> SrtfSortingPolicy()
            "RANDOM" -> RandomSortingPolicy()
            "HEFT" -> HeftSortingPolicy()
            "PISA" -> PisaSortingPolicy()
            "CPOP" -> CpopSortingPolicy()
            "FCP" -> FCPSortingPolicy()
            else -> return null
        }

        val selectionPolicy = when(selection) {
            "FIRSTFIT" -> FirstFitMachineSelectionPolicy()
            "BESTFIT" -> BestFitMachineSelectionPolicy()
            "WORSTFIT" -> WorstFitMachineSelectionPolicy()
            "RANDOM" -> RandomMachineSelectionPolicy()
            "HEFT" -> HeftMachineSelectionPolicy()
            "ROUNDROBIN" -> RrMachineSelectionPolicy()
            "CPOP" -> CpopMachineSelectionPolicy()
            "FCP" -> FCPMachineSelectionPolicy()
            else -> return null
        }

        return StageScheduler(
            name = name,
            taskEligibilityFilteringPolicy = FunctionalTaskEligibilityFilteringPolicy(),
            taskSortingPolicy = sortingPolicy,
            machineDynamicFilteringPolicy =  FunctionalMachineDynamicFilteringPolicy(),
            machineSelectionPolicy = selectionPolicy
        )
    }

    /**
     * Converts the value stored in the entity attribute into the
     * data representation to be stored in the database.
     *
     * @param attribute the entity attribute value to be converted
     * @return the converted data to be stored in the database column
     */
    override fun convertToDatabaseColumn(attribute: Scheduler<*>): String = attribute.name
}
