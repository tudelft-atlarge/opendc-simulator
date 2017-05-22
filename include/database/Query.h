#pragma once
#include <string>
#include <assert.h>
#include <mysql.h>

namespace Database
{

	namespace Specializations
	{
		template<typename ReturnType>
		ReturnType getResult(int location, MYSQL_BIND *bindings);

		template<>
		inline int getResult<int>(int location, MYSQL_BIND *bindings)
		{
			return *reinterpret_cast<int *>(bindings[location].buffer);
		}

		template<>
		inline std::string getResult<std::string>(int location, MYSQL_BIND *bindings)
		{
			return std::string(reinterpret_cast<const char*>(bindings[location].buffer));
		}

		template<typename ValueType>
		void bind(ValueType value, int location, MYSQL_BIND *bindings);

		template<>
		inline void bind<int>(int value, int location, MYSQL_BIND *bindings)
		{
			bindings[location].buffer_type = MYSQL_TYPE_LONG;
			bindings[location].buffer = new int(value);
		}

		template<>
		inline void bind<float>(float value, int location, MYSQL_BIND *bindings)
		{	
			bindings[location].buffer_type = MYSQL_TYPE_FLOAT;
			bindings[location].buffer = new float(value);
		}
	}

	/*
		This structure contains the metadata per binding.
	*/
	struct BindingMetadata
	{
		/*
			The real length of the fetched data.
		*/
		unsigned long length;

		/*
			A flag to indicate whether an error occured while fetching the
			data.
		*/
		my_bool error;
	};


	template<typename ...ReturnTypes>
	class Query
	{
	public:
		explicit Query(std::string query) : statement(nullptr), content(query)
		{}

		/*
			Calls mysql_stmt_close on the statement.
		*/
		~Query()
		{
			if (statement != nullptr) {
				mysql_stmt_free_result(statement);
				mysql_stmt_close(statement);
			}
			free_bindings();
		}

		/*
			Calls mysql_stmt_prepare to prepare this query.
		*/
		void prepare(MYSQL *db)
		{
			// Allocate prepared statement
			if (statement == nullptr) {
				statement = mysql_stmt_init(db);
				assert(statement != nullptr);
			}

			// Preprare statement
			int rc = mysql_stmt_prepare(statement, content.c_str(), static_cast<int>(content.size()));

			if (rc != 0) {
				fprintf(stderr, "error (%d): %s [%s]\n",
					mysql_errno(db), mysql_error(db), mysql_sqlstate(db));
				abort();
			}

			parameters_size = mysql_stmt_param_count(statement);
			parameters.clear();
			parameters.resize(parameters_size);

			// Fetch result set meta information
			auto meta = mysql_stmt_result_metadata(statement);

			// Get total columns in the query if the result metadata exists,
			// otherwise we assume there are no columns in the result set
			results_size = meta ? mysql_num_fields(meta) : 0;

			results.clear();
			results.resize(results_size);
			results_meta.clear();
			results_meta.resize(results_size);

			// Initialise the results bindings per column
			for (int i = 0; i < results_size; i++) {
				// Fetch metadata about particular field
				auto field = mysql_fetch_field_direct(meta, i);

				results[i].buffer = malloc(field->max_length);
				results[i].buffer_length = field->max_length;
				results[i].buffer_type = field->type;
				results[i].length = &(results_meta[i].length);
				results[i].is_null = nullptr;
				results[i].error = &(results_meta[i].error);
			}
		}

		/*
			Execute this query.
		*/
		void execute()
		{
			int rc;

			// Bind parameters to query
			rc = mysql_stmt_bind_param(statement, parameters.data());
			assert(rc == 0);

			// Bind results of the query, but only if there are columns in the
			// result set, otherwise the mysql call will fail.
			if (results_size > 0) {
				rc = mysql_stmt_bind_result(statement, results.data());
				assert(rc == 0);
			}

			// Execute the query
			rc = mysql_stmt_execute(statement);

			if (rc != 0) {
				fprintf(stderr, "error: %s (%d)\n",
						mysql_stmt_error(statement),
						mysql_stmt_errno(statement));
				abort();
			}

			// Buffer the results of the query client side
			// NOTE: This may be memory inefficient, but without this call
			// we get a command out of sync error due to query fetches
			// interleaving.
			rc = mysql_stmt_store_result(statement);
			assert(rc == 0);

			// Start at offset zero
			offset = 0;
		}


		/*
			Steps the execution of this query once. Returns true if the return code is SQLITE_ROW.
			This method will execute the query if it has not been executed yet.
		*/
		bool step()
		{
			int rc;

			// Execute statement if needed
			if (!executed) {
				execute();
				executed = true;
			}

			// If there exists no result set, calling mysql_stmt_fetch gives
			// an Command out of sync error, so indicate we are done with the
			// query.
			if (results_size == 0) {
				return false;
			}

			// Fetch the next row from the database
			rc = mysql_stmt_fetch(statement);

			if (rc == 0 || rc == MYSQL_DATA_TRUNCATED) {
				// Test if the data has been truncated which means that the
				// data in one of the columns has exceeded the size of the buffer.
				//
				// In order to fix this, we check all columns if the actual
				// length exceeds the buffer length and resize accordingly.

				for (int i = 0; i < results_size; i++) {
					int length = results_meta[i].length;
					// Determine if the buffer size is too small
					if (length <= results[i].buffer_length) {
						continue;
					}

					// Create a buffer buffer for the column
					results[i].buffer = realloc(results[i].buffer, length);
					results[i].buffer_length = length;

					// Refetch the column
					rc = mysql_stmt_fetch_column(statement, &results[i], i, offset);

					if (rc != 0) {
						fprintf(stderr, "error: %s (%d)\n",
							mysql_stmt_error(statement),
							mysql_stmt_errno(statement));
						abort();
					}
				}

				offset++;
				return true;
			} else if (rc == MYSQL_NO_DATA) {
				// No more data to be fetched
				return false;
			}

			fprintf(stderr, "error: %s (%d)\n",
					mysql_stmt_error(statement), mysql_stmt_errno(statement));
			abort();
		}

		/*
			Resets this query back to its initial state.
		*/
		void reset()
		{
			executed = false;
			mysql_stmt_free_result(statement);
			mysql_stmt_reset(statement);
		}

		/*
			A template for implementing the binding of values to parameters in the sqlite statement.
		*/
		template<typename ValueType>
		void bind(ValueType value, int location)
		{
			Specializations::bind<ValueType>(value, location, parameters.data());
		}

		/**
		 * \brief Returns the result of ReturnType at the given location in the query result row.
		 * \tparam ReturnType The type of the entry in the row.
		 * \param location The index of the entry in the row.
		 * \return The result of the query at the given location.
		 */
		template<typename ReturnType>
		ReturnType getResult(int location)
		{
			return Specializations::getResult<ReturnType>(location, results.data());
		}

	private:
		/*
			Deallocate the bindings if possible.
		*/
		void free_bindings() const {
			for (auto & parameter : parameters) {
				free(parameter.buffer);
			}

			for (auto & result : results) {
				free(result.buffer);
			}
		}

		// The MariaDB statement that corresponds to this query.
		MYSQL_STMT *statement;
		// Boolean to indicate the statement has been executed
		bool executed;
		// The row offset in the result set
		unsigned long offset;
		// The amount of parameter bindings
		int parameters_size;
		// The MariaDB parameter bindings
		std::vector<MYSQL_BIND> parameters;
		// The amount of columns
		int results_size;
		// The results bindings
		std::vector<MYSQL_BIND> results;
		// The results metadata
		std::vector<BindingMetadata> results_meta;
		// The sql string that will be executed.
		std::string content;
	};

}
