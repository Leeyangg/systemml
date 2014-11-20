/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */


package com.ibm.bi.dml.runtime.matrix.mapred;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.OutputCollector;

import com.ibm.bi.dml.runtime.matrix.data.AdaptivePartialBlock;
import com.ibm.bi.dml.runtime.matrix.data.IJV;
import com.ibm.bi.dml.runtime.matrix.data.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.data.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.data.PartialBlock;
import com.ibm.bi.dml.runtime.matrix.data.SparseRowsIterator;
import com.ibm.bi.dml.runtime.matrix.data.TaggedAdaptivePartialBlock;

/**
 * 
 * 
 */
public class ReblockBuffer 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2014\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	//default buffer size: 5M -> 5M * 3x8B = 120MB 
	public static final int DEFAULT_BUFFER_SIZE = 5000000;

	//buffer <long rowindex, long colindex, long value>
	//(pure long buffer for sort on flush) 
	private long[][] _buff = null;
	
	private int _bufflen = -1;
	private int _count = -1;
	
	private long _rlen = -1;
	private long _clen = -1;
	private int _brlen = -1;
	private int _bclen = -1;
	
	public ReblockBuffer( long rlen, long clen, int brlen, int bclen )
	{
		this( DEFAULT_BUFFER_SIZE, rlen, clen, brlen, bclen );
	}
	
	public ReblockBuffer( int buffersize, long rlen, long clen, int brlen, int bclen  )
	{
		//System.out.println("Creating reblock buffer of size "+buffersize);
		
		_bufflen = buffersize;
		_count = 0;
		
		_buff = new long[ _bufflen ][3];
		
		_rlen = rlen;
		_clen = clen;
		_brlen = brlen;
		_bclen = bclen;
	}
	
	/**
	 * 
	 * @param r
	 * @param c
	 * @param v
	 */
	public void appendCell( long r, long c, double v )
	{
		long tmp = Double.doubleToRawLongBits(v);
		_buff[_count][0] = r;
		_buff[_count][1] = c;
		_buff[_count][2] = tmp;
		_count++;
	}
	
	/**
	 * 
	 * @param r_offset
	 * @param c_offset
	 * @param inBlk
	 * @param index
	 * @param out
	 * @throws IOException
	 */
	public void appendBlock(long r_offset, long c_offset, MatrixBlock inBlk, byte index, OutputCollector<Writable, Writable> out ) 
		throws IOException
	{
		if( inBlk.isInSparseFormat() ) //SPARSE
		{
			SparseRowsIterator iter = inBlk.getSparseRowsIterator();
			while( iter.hasNext() )
			{
				IJV cell = iter.next();
				long tmp = Double.doubleToRawLongBits(cell.v);
				_buff[_count][0] = r_offset + cell.i;
				_buff[_count][1] = c_offset + cell.j;
				_buff[_count][2] = tmp;
				_count++;
				
				//check and flush if required
				if( _count ==_bufflen )
					flushBuffer(index, out);
			}
		}
		else //DENSE
		{
			//System.out.println("dense merge with ro="+r_offset+", co="+c_offset);
			int rlen = inBlk.getNumRows();
			int clen = inBlk.getNumColumns();
			for( int i=0; i<rlen; i++ )
				for( int j=0; j<clen; j++ )
				{
					double val = inBlk.getValueDenseUnsafe(i, j);
					if( val !=0 )
					{
						long tmp = Double.doubleToRawLongBits(val);
						_buff[_count][0] = r_offset + i;
						_buff[_count][1] = c_offset + j;
						_buff[_count][2] = tmp;
						_count++;
						
						//check and flush if required
						if( _count ==_bufflen )
							flushBuffer(index, out);
					}
				}
		}
	}
	
	public int getSize()
	{
		return _count;
	}
	
	public int getCapacity()
	{
		return _bufflen;
	}
	
	/**
	 * 
	 * @param index
	 * @param out
	 * @throws IOException
	 */
	public void flushBuffer( byte index, OutputCollector<Writable, Writable> out ) 
		throws IOException
	{
		if( _count == 0 )
			return;
		
		//Step 1) sort reblock buffer (blockwise, no in-block sorting!)
		Arrays.sort( _buff, 0 ,_count, new ReblockBufferComparator() );
		
		//Step 2) scan for number of created blocks
		long numBlocks = 0; //number of blocks in buffer
		long cbi = -1, cbj = -1; //current block indexes
		for( int i=0; i<_count; i++ )
		{
			long bi = getBlockIndex(_buff[i][0], _brlen);
			long bj = getBlockIndex(_buff[i][1], _bclen);
			
			//switch to next block
			if( bi != cbi || bj != cbj ) {
				cbi = bi;
				cbj = bj;
				numBlocks++;
			}
		}
		
		//Step 3) decide on intermediate representation (for entire buffer)
		//decision based on binarycell vs binaryblock_ultrasparse (worstcase)
		long blockedSize = 16 * numBlocks + 16 * _count; //<long,long>,#<int,int,double>
		long cellSize = 24 * _count; //#<long,long>,<double>
		boolean blocked = ( blockedSize <= cellSize );
		
		//Step 4) output blocks / binary cell (one-at-a-time)
		TaggedAdaptivePartialBlock outTVal = new TaggedAdaptivePartialBlock();
		AdaptivePartialBlock outVal = new AdaptivePartialBlock();
		MatrixIndexes tmpIx = new MatrixIndexes();
		outTVal.setTag(index);
		outTVal.setBaseObject(outVal); //setup wrapper writables
		if( blocked ) //output binaryblock
		{
			//create intermediate blocks
			boolean sparse = MatrixBlock.evalSparseFormatInMemory(_brlen, _bclen, _count/numBlocks);
			MatrixBlock tmpBlock = new MatrixBlock();
			
			//put values into block and output
			cbi = -1; cbj = -1; //current block indexes
			for( int i=0; i<_count; i++ )
			{
				long bi = getBlockIndex(_buff[i][0], _brlen);
				long bj = getBlockIndex(_buff[i][1], _bclen);
				
				//output block and switch to next index pair
				if( bi != cbi || bj != cbj ) {
					outputBlock(out, tmpIx, outTVal, tmpBlock);
					cbi = bi;
					cbj = bj;					
					tmpIx.setIndexes(bi, bj);
					tmpBlock.reset(Math.min(_brlen, (int)(_rlen-(bi-1)*_brlen)),
							       Math.min(_bclen, (int)(_clen-(bj-1)*_bclen)), sparse);
				}
				
				int ci = getIndexInBlock(_buff[i][0], _brlen);
				int cj = getIndexInBlock(_buff[i][1], _bclen);
				double tmp = Double.longBitsToDouble(_buff[i][2]);
				tmpBlock.appendValue(ci, cj, tmp); 
			}
			
			//output last block 
			outputBlock(out, tmpIx, outTVal, tmpBlock);
		}
		else //output binarycell
		{
			PartialBlock tmpVal = new PartialBlock();
			outVal.set(tmpVal);
			for( int i=0; i<_count; i++ )
			{
				long bi = getBlockIndex(_buff[i][0], _brlen);
				long bj = getBlockIndex(_buff[i][1], _bclen);
				int ci = getIndexInBlock(_buff[i][0], _brlen);
				int cj = getIndexInBlock(_buff[i][1], _bclen);
				double tmp = Double.longBitsToDouble(_buff[i][2]);
				tmpIx.setIndexes(bi, bj);
				tmpVal.set(ci, cj, tmp); //in outVal, in outTVal
				out.collect(tmpIx, outTVal);
			}
		}
		
		_count = 0;
	}
	
	/**
	 * 
	 * @param ix
	 * @param blen
	 * @return
	 */
	private static long getBlockIndex( long ix, int blen )
	{
		return (ix-1)/blen+1;
	}
	
	/**
	 * 
	 * @param ix
	 * @param blen
	 * @return
	 */
	private static int getIndexInBlock( long ix, int blen )
	{
		return (int)((ix-1)%blen);
	}
	
	/**
	 * 
	 * @param out
	 * @param key
	 * @param value
	 * @param block
	 * @throws IOException
	 */
	private static void outputBlock( OutputCollector<Writable, Writable> out, MatrixIndexes key, TaggedAdaptivePartialBlock value, MatrixBlock block ) 
		throws IOException
	{
		//skip output of unassigned blocks
		if( key.getRowIndex() == -1 || key.getColumnIndex() == -1 )
			return;
		
		//sort sparse rows due to blockwise buffer sort and append  
		if( block.isInSparseFormat() )
			block.sortSparseRows();
		
		//output block
		value.getBaseObject().set(block);
		out.collect(key, value);
	}
	
	
	
	/**
	 * Comparator to sort the reblock buffer by block indexes, where we 
	 * compute the block indexes on-the-fly based on the given cell indexes.
	 * 
	 */
	private class ReblockBufferComparator implements Comparator<long[]> 
	{	
		@Override
		public int compare(long[] arg0, long[] arg1) 
		{
			long bi0 = getBlockIndex( arg0[0], _brlen );
			long bj0 = getBlockIndex( arg0[1], _bclen );
			long bi1 = getBlockIndex( arg1[0], _brlen );
			long bj1 = getBlockIndex( arg1[1], _bclen );
			
			return ( bi0 < bi1 || (bi0 == bi1 && bj0 < bj1) ) ? -1 :
                   (( bi0 == bi1 && bj0 == bj1)? 0 : 1);		
		}		
	}
}
