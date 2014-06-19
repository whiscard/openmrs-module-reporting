/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.reporting.evaluation.service;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Query;
import org.hibernate.SessionFactory;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.reporting.ReportingConstants;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.EvaluationIdSet;
import org.openmrs.module.reporting.evaluation.EvaluationLogger;
import org.openmrs.module.reporting.evaluation.querybuilder.QueryBuilder;
import org.openmrs.module.reporting.query.IdSet;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the EvaluationService interface
 */
public class EvaluationServiceImpl extends BaseOpenmrsService implements EvaluationService {

	private transient Log log = LogFactory.getLog(this.getClass());
	private final List<String> currentIdSetKeys = Collections.synchronizedList(new ArrayList<String>());


	/**
	 * Since we need to insert/delete into the reporting_idset tables even during the course of read-only transactions,
	 * (and we need this to be committed as quickly as possible to avoid deadlocks due to table locking), we
	 * programmatically open and close the smallest possible transaction
	 */
	private PlatformTransactionManager transactionManager;

	public void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

    /**
	 * @see EvaluationService#evaluateToList(QueryBuilder)
	 */
	@Override
	@Transactional(readOnly = true)
	public List<Object[]> evaluateToList(QueryBuilder queryBuilder) {
		List<Object[]> ret = new ArrayList<Object[]>();
		for (Object resultRow : queryBuilder.listResults(getSessionFactory())) {
			if (resultRow instanceof Object[]) {
				ret.add((Object[])resultRow);
			}
			else {
				ret.add(new Object[]{resultRow});
			}
		}
		return ret;
	}

	/**
	 * @see EvaluationService#evaluateToList(QueryBuilder, Class)
	 */
	@Override
	@Transactional(readOnly = true)
	public <T> List<T> evaluateToList(QueryBuilder queryBuilder, Class<T> type) {
		List<T> ret = new ArrayList<T>();
		for (Object resultRow : queryBuilder.listResults(getSessionFactory())) {
			if (resultRow instanceof Object[]) {
				throw new IllegalArgumentException("Unable to evaluate to a single value list. Exactly one column must be defined.");
			}
			ret.add((T)resultRow);
		}
		return ret;
	}

	/**
	 * @see EvaluationService#evaluateToMap(QueryBuilder, Class, Class)
	 */
	@Override
	@Transactional(readOnly = true)
	public <K, V> Map<K, V> evaluateToMap(QueryBuilder queryBuilder, Class<K> keyType, Class<V> valueType) {
		Map<K, V> ret = new HashMap<K, V>();
		for (Object resultRow : queryBuilder.listResults(getSessionFactory())) {
			boolean found = false;
			if (resultRow instanceof Object[]) {
				Object[] results = (Object[])resultRow;
				if (results.length == 2) {
					ret.put((K)results[0], (V)results[1]);
					found = true;
				}
			}
			if (!found) {
				throw new IllegalArgumentException("Unable to evaluate to a map. Exactly two columns must be defined.");
			}
		}
		return ret;
	}

    /**
     * @see EvaluationService#generateKey(EvaluationIdSet)
     */
    @Override
    public String generateKey(EvaluationIdSet idSet) {
		return idSet.getEvaluationKey();
    }

	/**
	 * @see EvaluationService#startUsing(EvaluationIdSet)
	 */
	@Transactional
	@Override
	public String startUsing(EvaluationIdSet ids) {
		if (ids == null || ids.isEmpty() || !ReportingConstants.GLOBAL_PROPERTY_IDSET_JOINING_ENABLED()) {
			return null;
		}
		String idSetKey = generateKey(ids);
        synchronized (currentIdSetKeys) {
			EvaluationLogger.logBeforeEvent("startUsingIdSet", idSetKey);
			try {
				if (isInUse(idSetKey)) {
					log.debug("Attempting to persist an IdSet that has previously been persisted.  Using existing values.");
					Query testQuery = getSessionFactory().getCurrentSession().createSQLQuery("select count(*) from reporting_idset where idset_key = '" + idSetKey + "'");
					Object testQueryResult = testQuery.uniqueResult();
					if (!testQueryResult.toString().equals(Integer.toString(ids.size()))) {
						throw new IllegalStateException("***** EXPECTED " + ids.size() + " IN THE DB, BUT QUERY RETURNED " + testQueryResult);
					}
					// TODO: As an additional check here, we could confirm that they are the same by loading into memory
				} else {
					StringBuilder q = new StringBuilder();
					q.append("insert into reporting_idset (idset_key, member_id) values ");
					for (Iterator<Integer> i = ids.iterator(); i.hasNext(); ) {
						Integer id = i.next();
						q.append("('").append(idSetKey).append("',").append(id).append(")").append(i.hasNext() ? "," : "");
					}
					EvaluationLogger.logBeforeEvent("insertIdSet", idSetKey + "; size: " + ids.size());
					try {
						executeUpdate(q.toString());
					}
					finally {
						EvaluationLogger.logAfterEvent("insertIdSet", idSetKey);
					}
				}
				currentIdSetKeys.add(idSetKey);
			}
			finally {
				EvaluationLogger.logAfterEvent("startUsingIdSet", idSetKey);
			}
        }
		return idSetKey;
	}

	/**
	 * @see EvaluationService#startUsing(EvaluationContext)
	 */
	@Transactional
	@Override
	public List<String> startUsing(EvaluationContext context) {
		List<String> idSetsAdded = new ArrayList<String>();
		for (IdSet<?> idSet : context.getAllBaseIdSets().values()) {
			if (idSet != null && !idSet.getMemberIds().isEmpty()) {
				String key = startUsing(new EvaluationIdSet(context.getEvaluationId(), idSet.getMemberIds()));
				if (key != null) {
					idSetsAdded.add(key);
				}
			}
		}
		return idSetsAdded;
	}

	/**
	 * @see EvaluationService#isInUse(String)
	 */
	@Override
	public boolean isInUse(String idSetKey) {
        synchronized (currentIdSetKeys) {
            return currentIdSetKeys.contains(idSetKey);
        }
	}

	/**
	 * @see EvaluationService#stopUsing(String)
	 */
	@Transactional
	@Override
	public void stopUsing(String idSetKey) {
		if (idSetKey != null) {
			synchronized (currentIdSetKeys) {
				EvaluationLogger.logBeforeEvent("stopUsingIdSet", idSetKey);
				try {
					int indexToRemove = currentIdSetKeys.lastIndexOf(idSetKey);
					if (indexToRemove != -1) {
						currentIdSetKeys.remove(indexToRemove);
					}
					if (!currentIdSetKeys.contains(idSetKey)) {
						EvaluationLogger.logBeforeEvent("deleteIdSet", idSetKey);
						try {
							executeUpdate("delete from reporting_idset where idset_key = '" + idSetKey + "'");
							currentIdSetKeys.remove(idSetKey);
						}
						finally {
							EvaluationLogger.logAfterEvent("deleteIdSet", idSetKey);
						}
					}
				}
				finally {
					EvaluationLogger.logAfterEvent("stopUsingIdSet", idSetKey);
				}
			}
		}
	}

	/**
	 * @see EvaluationService#stopUsing(EvaluationContext)
	 */
	@Transactional
	@Override
	public void stopUsing(EvaluationContext context) {
		for (IdSet<?> idSet : context.getAllBaseIdSets().values()) {
			if (idSet != null && !idSet.getMemberIds().isEmpty()) {
				stopUsing(generateKey(new EvaluationIdSet(context.getEvaluationId(), idSet.getMemberIds())));
			}
		}
	}

	/**
	 * @see EvaluationService#resetAllIdSets()
	 */
	@Transactional
	@Override
	public void resetAllIdSets() {
        synchronized (currentIdSetKeys) {
            currentIdSetKeys.clear();
            executeUpdate("delete from reporting_idset");
        }
	}

    private void executeUpdate(String sql) {
		TransactionStatus tx = transactionManager.getTransaction(new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
		try {
			Query query = getSessionFactory().getCurrentSession().createSQLQuery(sql);
			query.executeUpdate();
			transactionManager.commit(tx);
		}
		catch (Exception ex) {
			transactionManager.rollback(tx);
			throw new IllegalStateException("Failed to execute sql: " + sql, ex);
		}
	}

	private SessionFactory getSessionFactory() {
		return Context.getRegisteredComponents(SessionFactory.class).get(0);
	}
}