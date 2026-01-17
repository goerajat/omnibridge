package com.fixengine.message;

/**
 * Standard FIX protocol tag numbers.
 */
public final class FixTags {

    private FixTags() {
    }

    // Header fields
    public static final int BeginString = 8;
    public static final int BodyLength = 9;
    public static final int MsgType = 35;
    public static final int SenderCompID = 49;
    public static final int TargetCompID = 56;
    public static final int MsgSeqNum = 34;
    public static final int SendingTime = 52;
    public static final int PossDupFlag = 43;
    public static final int PossResend = 97;
    public static final int OrigSendingTime = 122;

    // Aliases using UPPER_SNAKE_CASE for convenience
    public static final int BEGIN_STRING = BeginString;
    public static final int BODY_LENGTH = BodyLength;
    public static final int MSG_TYPE = MsgType;
    public static final int SENDER_COMP_ID = SenderCompID;
    public static final int TARGET_COMP_ID = TargetCompID;
    public static final int MSG_SEQ_NUM = MsgSeqNum;
    public static final int SENDING_TIME = SendingTime;
    public static final int POSS_DUP_FLAG = PossDupFlag;
    public static final int ORIG_SENDING_TIME = OrigSendingTime;

    // Trailer fields
    public static final int CheckSum = 10;
    public static final int CHECKSUM = CheckSum;

    // Session messages
    public static final int EncryptMethod = 98;
    public static final int HeartBtInt = 108;
    public static final int ResetSeqNumFlag = 141;
    public static final int TestReqID = 112;
    public static final int BeginSeqNo = 7;
    public static final int EndSeqNo = 16;
    public static final int RefSeqNum = 45;
    public static final int RefTagID = 371;
    public static final int RefMsgType = 372;
    public static final int SessionRejectReason = 373;
    public static final int BusinessRejectReason = 380;
    public static final int Text = 58;
    public static final int GapFillFlag = 123;
    public static final int NewSeqNo = 36;

    // Session message aliases
    public static final int ENCRYPT_METHOD = EncryptMethod;
    public static final int HEARTBT_INT = HeartBtInt;
    public static final int RESET_SEQ_NUM_FLAG = ResetSeqNumFlag;
    public static final int TEST_REQ_ID = TestReqID;
    public static final int BEGIN_SEQ_NO = BeginSeqNo;
    public static final int END_SEQ_NO = EndSeqNo;
    public static final int REF_SEQ_NUM = RefSeqNum;
    public static final int REF_MSG_TYPE = RefMsgType;
    public static final int SESSION_REJECT_REASON = SessionRejectReason;
    public static final int BUSINESS_REJECT_REASON = BusinessRejectReason;
    public static final int TEXT = Text;
    public static final int GAP_FILL_FLAG = GapFillFlag;
    public static final int NEW_SEQ_NO = NewSeqNo;

    // Session reject reasons
    public static final int SESSION_REJECT_REASON_INVALID_TAG_NUMBER = 0;
    public static final int SESSION_REJECT_REASON_REQUIRED_TAG_MISSING = 1;
    public static final int SESSION_REJECT_REASON_TAG_NOT_DEFINED = 2;
    public static final int SESSION_REJECT_REASON_UNDEFINED_TAG = 3;
    public static final int SESSION_REJECT_REASON_TAG_SPECIFIED_WITHOUT_VALUE = 4;
    public static final int SESSION_REJECT_REASON_VALUE_INCORRECT = 5;
    public static final int SESSION_REJECT_REASON_INCORRECT_DATA_FORMAT = 6;
    public static final int SESSION_REJECT_REASON_DECRYPTION_PROBLEM = 7;
    public static final int SESSION_REJECT_REASON_SIGNATURE_PROBLEM = 8;
    public static final int SESSION_REJECT_REASON_COMP_ID_PROBLEM = 9;
    public static final int SESSION_REJECT_REASON_SENDING_TIME_ACCURACY = 10;
    public static final int SESSION_REJECT_REASON_INVALID_MSG_TYPE = 11;
    public static final int SESSION_REJECT_REASON_OTHER = 99;

    // Message type constants as strings
    public static final String MSG_TYPE_HEARTBEAT = "0";
    public static final String MSG_TYPE_TEST_REQUEST = "1";
    public static final String MSG_TYPE_RESEND_REQUEST = "2";
    public static final String MSG_TYPE_REJECT = "3";
    public static final String MSG_TYPE_SEQUENCE_RESET = "4";
    public static final String MSG_TYPE_LOGOUT = "5";
    public static final String MSG_TYPE_LOGON = "A";
    public static final String MSG_TYPE_BUSINESS_REJECT = "j";

    // Order fields
    public static final int ClOrdID = 11;
    public static final int OrderID = 37;
    public static final int ExecID = 17;
    public static final int ExecType = 150;
    public static final int OrdStatus = 39;
    public static final int Symbol = 55;
    public static final int Side = 54;
    public static final int OrderQty = 38;
    public static final int OrdType = 40;
    public static final int Price = 44;
    public static final int TimeInForce = 59;
    public static final int TransactTime = 60;
    public static final int CumQty = 14;
    public static final int AvgPx = 6;
    public static final int LeavesQty = 151;
    public static final int LastQty = 32;
    public static final int LastPx = 31;

    // Standard message types
    public static final class MsgTypes {
        public static final String Heartbeat = "0";
        public static final String TestRequest = "1";
        public static final String ResendRequest = "2";
        public static final String Reject = "3";
        public static final String SequenceReset = "4";
        public static final String Logout = "5";
        public static final String Logon = "A";
        public static final String NewOrderSingle = "D";
        public static final String ExecutionReport = "8";
        public static final String OrderCancelRequest = "F";
        public static final String OrderCancelReject = "9";
        public static final String OrderCancelReplaceRequest = "G";

        private MsgTypes() {
        }

        public static boolean isAdmin(String msgType) {
            return switch (msgType) {
                case Heartbeat, TestRequest, ResendRequest, Reject, SequenceReset, Logout, Logon -> true;
                default -> false;
            };
        }
    }

    // Side values
    public static final char SIDE_BUY = '1';
    public static final char SIDE_SELL = '2';

    // Order type values
    public static final char ORD_TYPE_MARKET = '1';
    public static final char ORD_TYPE_LIMIT = '2';
    public static final char ORD_TYPE_STOP = '3';
    public static final char ORD_TYPE_STOP_LIMIT = '4';

    // Time in force values
    public static final char TIF_DAY = '0';
    public static final char TIF_GTC = '1';
    public static final char TIF_IOC = '3';
    public static final char TIF_FOK = '4';

    // Exec type values
    public static final char EXEC_TYPE_NEW = '0';
    public static final char EXEC_TYPE_PARTIAL_FILL = '1';
    public static final char EXEC_TYPE_FILL = '2';
    public static final char EXEC_TYPE_CANCELED = '4';
    public static final char EXEC_TYPE_REPLACED = '5';
    public static final char EXEC_TYPE_PENDING_CANCEL = '6';
    public static final char EXEC_TYPE_REJECTED = '8';
    public static final char EXEC_TYPE_PENDING_NEW = 'A';
    public static final char EXEC_TYPE_PENDING_REPLACE = 'E';

    // Order status values
    public static final char ORD_STATUS_NEW = '0';
    public static final char ORD_STATUS_PARTIALLY_FILLED = '1';
    public static final char ORD_STATUS_FILLED = '2';
    public static final char ORD_STATUS_CANCELED = '4';
    public static final char ORD_STATUS_REPLACED = '5';
    public static final char ORD_STATUS_PENDING_CANCEL = '6';
    public static final char ORD_STATUS_REJECTED = '8';
    public static final char ORD_STATUS_PENDING_NEW = 'A';
    public static final char ORD_STATUS_PENDING_REPLACE = 'E';
}
