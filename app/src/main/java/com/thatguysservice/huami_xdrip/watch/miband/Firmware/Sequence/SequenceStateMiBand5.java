package com.thatguysservice.huami_xdrip.watch.miband.Firmware.Sequence;

public class SequenceStateMiBand5 extends SequenceState {
    public static final String UNKNOWN_REQUEST = "UNKNOWN_REQUEST";
    public static final String WAITING_UNKNOWN_REQUEST_RESPONSE = "WAITING_UNKNOWN_REQUEST_RESPONCE";

    {
        sequence.add(INIT);
        sequence.add(NOTIFICATION_ENABLE);
        sequence.add(TRANSFER_WF_ID);
        sequence.add(SET_NIGHTMODE);
        sequence.add(PREPARE_UPLOAD);
        sequence.add(WAITING_PREPARE_UPLOAD_RESPONSE);
        sequence.add(UNKNOWN_REQUEST);
        sequence.add(WAITING_UNKNOWN_REQUEST_RESPONSE);
        sequence.add(TRANSFER_SEND_WF_INFO);
        sequence.add(WAITING_TRANSFER_SEND_WF_INFO_RESPONSE);
        sequence.add(TRANSFER_FW_START);
        sequence.add(TRANSFER_FW_DATA);
        sequence.add(SEND_CHECKSUM);
        sequence.add(WAITING_SEND_CHECKSUM_RESPONSE);
        sequence.add(CHECKSUM_VERIFIED);
        sequence.add(FW_UPLOADING_FINISHED);
    }
}
