#pragma once
#include "simulation/Section.h"
#include "modeling/ModelingTypes.h"

#include <mysql.h>
#include "simulation/Experiment.h"

namespace Database
{
	/*
		The Database class provides a wrapper for the sqlite interface.
	*/
	class Database
	{
	public:
		/*
			Initializes a database connection to the database located at the
			given address using the given username and password..
		*/
		explicit Database(const char *host,
				const char *user,
				const char *passwd,
				const char *db,
				unsigned int port);
		
		/*
			Closes the database connection.
		*/
		~Database();

		/*
			Starts a sqlite transaction.
		*/
		void startTransaction() const;

		/*
			Ends a sqlite transaction.
		*/
		void endTransaction() const;

		/*
			Writes the history of the experiment to the database.
		*/
		void writeExperimentHistory(Simulation::Experiment& simulation) const;

		/*
			Polls the database for new simulation sections to simulate.
			If there are no rows in the table, this returns -1.
		*/
		int pollQueuedExperiments() const;

		/*
			Removes a row of the queued simulation sections.
		*/
		void dequeueExperiment(int id) const;

		/*
			Marks the given experiment as finished in the database.
		*/
		void finishExperiment(int id) const;

		/*
			Creates a simulation object from a simulation in the database.
		*/
		Simulation::Experiment createExperiment(uint32_t id);

	private:
		/*
			Sets the scheduler of the datacenter
		*/
		Simulation::Scheduler* loadScheduler(uint32_t simulationSectionId) const;

		DefaultDatacenter loadDatacenter(uint32_t datacenterId) const;

		/*
			Fills the datacenter with workloads from the database.
		*/
		Simulation::WorkloadPool loadWorkloads(uint32_t traceId) const;

		// The MariaDB db connection.
		MYSQL *db;
	};
}
