package org.elasticsearch.plugin.readonlyrest.acl.requestcontext;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest;
import org.elasticsearch.action.termvectors.TermVectorsRequest;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.plugin.readonlyrest.utils.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public class RCTransactionalIndices {

  private final static Logger logger = Loggers.getLogger(RCTransactionalIndices.class);


  public static Transactional<Set<String>> mkInstance(RequestContext rc) {
    return new Transactional<Set<String>>("rc-indices") {

      @Override
      public Set<String> initialize() {
        if (!rc.involvesIndices()) {
          throw new RCUtils.RRContextException("cannot get indices of a request that doesn't involve indices");
        }

        logger.info("Finding indices for: " + rc.getId());

        String[] indices = new String[0];
        ActionRequest ar = rc.getUnderlyingRequest();
        // CompositeIndicesRequests
        if (ar instanceof MultiGetRequest) {
          MultiGetRequest cir = (MultiGetRequest) ar;

          for (MultiGetRequest.Item ir : cir.getItems()) {
            indices = ArrayUtils.concat(indices, ir.indices(), String.class);
          }
        }
        else if (ar instanceof MultiSearchRequest) {
          MultiSearchRequest cir = (MultiSearchRequest) ar;

          for (SearchRequest ir : cir.requests()) {
            indices = ArrayUtils.concat(indices, ir.indices(), String.class);
          }
        }
        else if (ar instanceof MultiTermVectorsRequest) {
          MultiTermVectorsRequest cir = (MultiTermVectorsRequest) ar;

          for (TermVectorsRequest ir : cir.getRequests()) {
            indices = ArrayUtils.concat(indices, ir.indices(), String.class);
          }
        }
        else if (ar instanceof BulkRequest) {
          BulkRequest cir = (BulkRequest) ar;

          for (DocWriteRequest<?> ir : cir.requests()) {
            String[] docIndices = ReflectionUtils.extractStringArrayFromPrivateMethod("indices", ir, logger);
            if (docIndices.length == 0) {
              docIndices = ReflectionUtils.extractStringArrayFromPrivateMethod("index", ir, logger);
            }
            indices = ArrayUtils.concat(indices, docIndices, String.class);
          }
        }
        else if (ar instanceof CompositeIndicesRequest) {
          logger.error("Found an instance of CompositeIndicesRequest that could not be handled: report this as a bug immediately!");
        }
        else {
          indices = ReflectionUtils.extractStringArrayFromPrivateMethod("indices", ar, logger);
          if (indices.length == 0) {
            indices = ReflectionUtils.extractStringArrayFromPrivateMethod("index", ar, logger);
          }
        }

        if (indices == null) {
          indices = new String[0];
        }

        Set<String> indicesSet = org.elasticsearch.common.util.set.Sets.newHashSet(indices);

        if (logger.isDebugEnabled()) {
          String idxs = String.join(",", indicesSet);
          logger.debug("Discovered indices: " + idxs);
        }

        return indicesSet;
      }

      @Override
      public Set<String> copy(Set<String> initial) {
        return Sets.newHashSet(initial);
      }

      @Override
      public void onCommit(Set<String> newIndices) {
        // Setting indices by reflection..

        newIndices.remove("<no-index>");
        newIndices.remove("");
        ActionRequest actionRequest = rc.getUnderlyingRequest();

        if (newIndices.equals(getInitial())) {
          logger.info("id: " + rc.getId() + " - Not replacing. Indices are the same. Old:" + get() + " New:" + newIndices);
          return;
        }
        logger.info("id: " + rc.getId() + " - Replacing indices. Old:" + getInitial() + " New:" + newIndices);

        if (newIndices.size() == 0) {
          throw new ElasticsearchException(
              "Attempted to set empty indices list, this would allow full access, therefore this is forbidden." +
                  " If this was intended, set '*' as indices.");
        }

        Class<?> c = actionRequest.getClass();
        final List<Throwable> errors = Lists.newArrayList();

        errors.addAll(ReflectionUtils.fieldChanger(c, "indices", logger, rc,
            (Field f) -> {
              String[] idxArray = newIndices.toArray(new String[newIndices.size()]);
              f.set(actionRequest, idxArray);
              return null;
            }
        ));


        // Take care of writes
        if (!errors.isEmpty() && newIndices.size() == 1) {
          errors.clear();
          errors.addAll(ReflectionUtils.fieldChanger(c, "index", logger, rc, (f) -> {
            f.set(actionRequest, newIndices.iterator().next());
            return null;
          }));
        }

        if (!errors.isEmpty() && actionRequest instanceof IndicesAliasesRequest) {
          IndicesAliasesRequest iar = (IndicesAliasesRequest) actionRequest;
          List<IndicesAliasesRequest.AliasActions> actions = iar.getAliasActions();
          actions.forEach(a -> {
            errors.addAll(ReflectionUtils.fieldChanger(a.getClass(), "indices", logger, rc, (f) -> {
              String[] idxArray = newIndices.toArray(new String[newIndices.size()]);
              f.set(a, idxArray);
              return null;
            }));
          });
        }

        if (errors.isEmpty()) {
          logger.debug("success changing indices: " + newIndices + " correctly set as " + get());
        }
        else {
          errors.forEach(e -> {
            logger.error("Failed to set indices " + e.toString());
          });
        }
      }


    };
  }
}
