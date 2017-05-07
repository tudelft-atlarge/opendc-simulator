#pragma once
#include "Scheduler.h"
#include <algorithm>

namespace Simulation
{
	class FirstInFirstOutScheduler : public Scheduler
	{
	protected:
		~FirstInFirstOutScheduler()
		{
		}

	public:
		/**
		* \brief Distribute workloads according to the FIFO principle.
		*/
		void schedule(std::vector<std::reference_wrapper<Modeling::Machine>>& machines, std::vector<Workload*> workloads) override
		{
			if (workloads.size() == 0)
				return;

			// Find the first workload with dependencies finished
			int index = 0;

			std::remove_if(workloads.begin(), workloads.end(), [](Workload* workload) {
				return !workload->dependencyFinished;
			});

			// Reset the number of cores used for each workload
			for (auto workload : workloads) 
			{
				workload->setCoresUsed(0);
			}

			// Distribute tasks across machines and set cores used of workloads
			for (auto machine : machines) 
			{
				machine.get().giveTask(workloads.at(index));
				workloads.at(index)->setCoresUsed(
					workloads.at(index)->getCoresUsed() + machine.get().getNumberOfCores()
				);

				if (!workloads.at(index)->isParallelizable())
				{
					workloads.erase(workloads.begin() + index);
					if (workloads.size() == 0)
						break;

					index %= workloads.size();
				}
				else
				{
					index = (++index) % workloads.size();
				}
			}
		}
	};
}
