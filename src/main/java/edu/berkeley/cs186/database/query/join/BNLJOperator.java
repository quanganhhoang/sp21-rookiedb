package edu.berkeley.cs186.database.query.join;

import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.iterator.BacktrackingIterator;
import edu.berkeley.cs186.database.query.JoinOperator;
import edu.berkeley.cs186.database.query.QueryOperator;
import edu.berkeley.cs186.database.table.Record;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Performs an equijoin between two relations on leftColumnName and
 * rightColumnName respectively using the Block Nested Loop Join algorithm.
 */
public class BNLJOperator extends JoinOperator {
    protected int numBuffers;

    public BNLJOperator(QueryOperator leftSource,
                        QueryOperator rightSource,
                        String leftColumnName,
                        String rightColumnName,
                        TransactionContext transaction) {
        super(leftSource, materialize(rightSource, transaction),
                leftColumnName, rightColumnName, transaction, JoinType.BNLJ
        );
        this.numBuffers = transaction.getWorkMemSize();
        this.stats = this.estimateStats();
    }

    @Override
    public Iterator<Record> iterator() {
        return new BNLJIterator();
    }

    @Override
    public int estimateIOCost() {
        //This method implements the IO cost estimation of the Block Nested Loop Join
        int usableBuffers = numBuffers - 2;
        int numLeftPages = getLeftSource().estimateStats().getNumPages();
        int numRightPages = getRightSource().estimateIOCost();
        return ((int) Math.ceil((double) numLeftPages / (double) usableBuffers)) * numRightPages +
               getLeftSource().estimateIOCost();
    }

    /**
     * A record iterator that executes the logic for a simple nested loop join.
     * Look over the implementation in SNLJOperator if you want to get a feel
     * for the fetchNextRecord() logic.
     */
    private class BNLJIterator implements Iterator<Record>{
        // Iterator over all the records of the left source
        private Iterator<Record> leftSourceIterator;
        // Iterator over all the records of the right source
        private BacktrackingIterator<Record> rightSourceIterator;
        // Iterator over records in the current block of left pages
        private BacktrackingIterator<Record> leftBlockIterator;
        // Iterator over records in the current right page
        private BacktrackingIterator<Record> rightPageIterator;
        // The current record from the left relation
        private Record leftRecord;
        // The next record to return
        private Record nextRecord;

        private BNLJIterator() {
            super();
            this.leftSourceIterator = getLeftSource().iterator();
            this.fetchNextLeftBlock();

            this.rightSourceIterator = getRightSource().backtrackingIterator();
            this.rightSourceIterator.markNext();
            this.fetchNextRightPage();

            this.nextRecord = null;
        }

        /**
         * Fetch the next block of records from the left source.
         * leftBlockIterator should be set to a backtracking iterator over up to
         * B-2 pages of records from the left source, and leftRecord should be
         * set to the first record in this block.
         *
         * If there are no more records in the left source, this method should
         * do nothing.
         *
         * You may find QueryOperator#getBlockIterator useful here.
         */
        private void fetchNextLeftBlock() {
            if (this.leftSourceIterator.hasNext()) {
                this.leftBlockIterator = getBlockIterator(
                    this.leftSourceIterator,
                    BNLJOperator.this.getLeftSource().getSchema(),
                    BNLJOperator.this.numBuffers - 2
                );

                if (this.leftBlockIterator.hasNext()) {
                    this.leftRecord = this.leftBlockIterator.next();
                    this.leftBlockIterator.markPrev();
                }
            }
        }

        /**
         * Fetch the next page of records from the right source.
         * rightPageIterator should be set to a backtracking iterator over up to
         * one page of records from the right source.
         *
         * If there are no more records in the right source, this method should
         * do nothing.
         *
         * You may find QueryOperator#getBlockIterator useful here.
         */
        private void fetchNextRightPage() {
            if (this.rightSourceIterator.hasNext()) {
                this.rightPageIterator = getBlockIterator(
                    this.rightSourceIterator,
                    BNLJOperator.this.getRightSource().getSchema(),
                    1
                );
                this.rightPageIterator.markNext();
            }
        }

        /**
         * Returns the next record that should be yielded from this join,
         * or null if there are no more records to join.
         *
         * You may find JoinOperator#compare useful here. (You can call compare
         * function directly from this file, since BNLJOperator is a subclass
         * of JoinOperator).
         */
        private Record fetchNextRecord() {
            Record nextRecord = null;
            while (nextRecord == null) {
                if (this.leftRecord != null && this.rightPageIterator.hasNext()) {
                    // Case 1: The right page iterator has a value to yield
                    Record rightRecord = this.rightPageIterator.next();
                    if (compare(leftRecord, rightRecord) == 0) {
                        nextRecord = leftRecord.concat(rightRecord);
                    }
                } else if (this.leftBlockIterator.hasNext()) {
                    // Case 2: The right page iterator doesn't have a value to yield
                    // but the left block iterator does
                    this.leftRecord = this.leftBlockIterator.next();
                    this.rightPageIterator.reset();
                } else if (this.rightSourceIterator.hasNext()) {
                    // Case 3: Neither the right page nor left block iterators have values to yield,
                    // but there's more right pages
                    fetchNextRightPage();
                    this.leftBlockIterator.reset();
                    this.leftRecord = this.leftBlockIterator.next();
                } else if (this.leftSourceIterator.hasNext()) {
                    // Case 4: Neither right page nor left block iterators have values
                    // nor are there more right pages, but there are still left blocks
                    fetchNextLeftBlock();
                    this.rightSourceIterator.reset();
                    fetchNextRightPage();
                } else {
                    break;
                }
            }

            return nextRecord;
        }

        /**
         * @return true if this iterator has another record to yield, otherwise
         * false
         */
        @Override
        public boolean hasNext() {
            if (this.nextRecord == null) this.nextRecord = fetchNextRecord();
            return this.nextRecord != null;
        }

        /**
         * @return the next record from this iterator
         * @throws NoSuchElementException if there are no more records to yield
         */
        @Override
        public Record next() {
            if (!this.hasNext()) throw new NoSuchElementException();
            Record nextRecord = this.nextRecord;
            this.nextRecord = null;
            return nextRecord;
        }
    }
}
