/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.carbondata.scan.collector.impl;

import java.util.List;

import org.carbondata.common.logging.LogService;
import org.carbondata.common.logging.LogServiceFactory;
import org.carbondata.core.carbon.datastore.chunk.MeasureColumnDataChunk;
import org.carbondata.core.carbon.metadata.datatype.DataType;
import org.carbondata.core.keygenerator.KeyGenException;
import org.carbondata.core.util.DataTypeUtil;
import org.carbondata.scan.collector.ScannedResultCollector;
import org.carbondata.scan.executor.infos.BlockExecutionInfo;
import org.carbondata.scan.executor.infos.KeyStructureInfo;
import org.carbondata.scan.executor.util.QueryUtil;
import org.carbondata.scan.result.AbstractScannedResult;
import org.carbondata.scan.wrappers.ByteArrayWrapper;

/**
 * It is not a collector it is just a scanned result holder.
 */
public abstract class AbstractScannedResultCollector implements ScannedResultCollector {

  private static final LogService LOGGER =
      LogServiceFactory.getLogService(AbstractScannedResultCollector.class.getName());

  /**
   * restructuring info
   */
  private KeyStructureInfo restructureInfos;

  /**
   * table block execution infos
   */
  protected BlockExecutionInfo tableBlockExecutionInfos;

  /**
   * Measure ordinals
   */
  protected int[] measuresOrdinal;

  /**
   * to check whether measure exists in current table block or not this to
   * handle restructuring scenario
   */
  protected boolean[] isMeasureExistsInCurrentBlock;

  /**
   * default value of the measures in case of restructuring some measure wont
   * be present in the table so in that default value will be used to
   * aggregate the data for that measure columns
   */
  private Object[] measureDefaultValue;

  /**
   * measure datatypes.
   */
  protected DataType[] measureDatatypes;

  public AbstractScannedResultCollector(BlockExecutionInfo blockExecutionInfos) {
    this.tableBlockExecutionInfos = blockExecutionInfos;
    restructureInfos = blockExecutionInfos.getKeyStructureInfo();
    measuresOrdinal = tableBlockExecutionInfos.getAggregatorInfo().getMeasureOrdinals();
    isMeasureExistsInCurrentBlock = tableBlockExecutionInfos.getAggregatorInfo().getMeasureExists();
    measureDefaultValue = tableBlockExecutionInfos.getAggregatorInfo().getDefaultValues();
    this.measureDatatypes = tableBlockExecutionInfos.getAggregatorInfo().getMeasureDataTypes();
  }

  protected void fillMeasureData(Object[] msrValues, int offset,
      AbstractScannedResult scannedResult) {
    for (short i = 0; i < measuresOrdinal.length; i++) {
      // if measure exists is block then pass measure column
      // data chunk to the collector
      if (isMeasureExistsInCurrentBlock[i]) {
        msrValues[i + offset] = getMeasureData(scannedResult.getMeasureChunk(measuresOrdinal[i]),
            scannedResult.getCurrenrRowId(), measureDatatypes[i]);
      } else {
        // if not then get the default value and use that value in aggregation
        msrValues[i + offset] = measureDefaultValue[i];
      }
    }
  }

  private Object getMeasureData(MeasureColumnDataChunk dataChunk, int index, DataType dataType) {
    if (!dataChunk.getNullValueIndexHolder().getBitSet().get(index)) {
      Object msrVal;
      switch (dataType) {
        case INT:
        case LONG:
          msrVal = dataChunk.getMeasureDataHolder().getReadableLongValueByIndex(index);
          break;
        case DECIMAL:
          msrVal = dataChunk.getMeasureDataHolder().getReadableBigDecimalValueByIndex(index);
          break;
        default:
          msrVal = dataChunk.getMeasureDataHolder().getReadableDoubleValueByIndex(index);
      }
      return DataTypeUtil.getMeasureDataBasedOnDataType(msrVal, dataType);
    }
    return null;
  }

  /**
   * Below method will used to get the result
   */
  protected void updateData(List<Object[]> listBasedResult) {
    if (tableBlockExecutionInfos.isFixedKeyUpdateRequired()) {
      updateKeyWithLatestBlockKeygenerator(listBasedResult);
    }
  }

  /**
   * Below method will be used to update the fixed length key with the
   * latest block key generator
   *
   * @return updated block
   */
  private void updateKeyWithLatestBlockKeygenerator(List<Object[]> listBasedResult) {
    try {
      long[] data = null;
      ByteArrayWrapper key = null;
      for (int i = 0; i < listBasedResult.size(); i++) {
        // get the key
        key = (ByteArrayWrapper)listBasedResult.get(i)[0];
        // unpack the key with table block key generator
        data = tableBlockExecutionInfos.getBlockKeyGenerator()
            .getKeyArray(key.getDictionaryKey(), tableBlockExecutionInfos.getMaskedByteForBlock());
        // packed the key with latest block key generator
        // and generate the masked key for that key
        key.setDictionaryKey(QueryUtil
            .getMaskedKey(restructureInfos.getKeyGenerator().generateKey(data),
                restructureInfos.getMaxKey(), restructureInfos.getMaskByteRanges(),
                restructureInfos.getMaskByteRanges().length));
      }
    } catch (KeyGenException e) {
      LOGGER.error(e);
    }
  }

}
