package history.model;

import core.model.FileInfo;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class HistoryRecord {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String id;
    public HistoryType type;
    public RecordCategory category;
    public String description;
    public String result;
    public String details;
    public String inputText;
    public String outputText;
    public String keypairName;
    public String keyPairName;
    public String contactName;
    public String fileInputPath;
    public String fileOutputPath;
    public String comment;
    public String notes;
    public String timestamp;
    public long timestampMillis;
    public long durationMs;
    public boolean starred;
    public List<FileInfo> inputFiles;
    public List<FileInfo> outputFiles;
    public Boolean isSplit;
    public Integer totalVolumes;
    public Integer currentVolume;
    public String operationType;
    public boolean success;

    public HistoryRecord() {
        this.inputFiles = new ArrayList<>();
        this.outputFiles = new ArrayList<>();
    }

    public HistoryRecord(HistoryType type, String description, boolean success, String result, String details) {
        this();
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.description = description;
        this.result = result;
        this.details = details;
        this.timestamp = LocalDateTime.now().format(FORMATTER);
        this.timestampMillis = System.currentTimeMillis();
        this.starred = false;
        this.success = success;
    }

    public HistoryRecord(HistoryType type, RecordCategory category, String description, 
                       boolean success, String result, String details) {
        this(type, description, success, result, details);
        this.category = category;
    }

    public HistoryRecord(String operationType, String inputText, String outputText, String keypairName, 
                       String contactName, String fileInputPath, String fileOutputPath, String comment) {
        this();
        this.id = UUID.randomUUID().toString();
        this.operationType = operationType;
        this.inputText = inputText;
        this.outputText = outputText;
        this.keypairName = keypairName;
        this.keyPairName = keypairName;
        this.contactName = contactName;
        this.fileInputPath = fileInputPath;
        this.fileOutputPath = fileOutputPath;
        this.comment = comment;
        this.timestamp = LocalDateTime.now().format(FORMATTER);
        this.timestampMillis = System.currentTimeMillis();
        this.isSplit = false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public HistoryType getType() { return type; }
    public void setType(HistoryType type) { this.type = type; }

    public RecordCategory getCategory() { return category; }
    public void setCategory(RecordCategory category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public String getInputText() { return inputText; }
    public void setInputText(String inputText) { this.inputText = inputText; }

    public String getOutputText() { return outputText; }
    public void setOutputText(String outputText) { this.outputText = outputText; }

    public String getKeypairName() { return keypairName; }
    public void setKeypairName(String keypairName) { this.keypairName = keypairName; }

    public String getKeyPairName() { return keyPairName != null ? keyPairName : keypairName; }
    public void setKeyPairName(String keyPairName) { 
        this.keyPairName = keyPairName; 
        this.keypairName = keyPairName; 
    }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getFileInputPath() { return fileInputPath; }
    public void setFileInputPath(String fileInputPath) { this.fileInputPath = fileInputPath; }

    public String getFileOutputPath() { return fileOutputPath; }
    public void setFileOutputPath(String fileOutputPath) { this.fileOutputPath = fileOutputPath; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public long getTimestampMillis() { return timestampMillis; }
    public void setTimestampMillis(long timestampMillis) { this.timestampMillis = timestampMillis; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public boolean isStarred() { return starred; }
    public void setStarred(boolean starred) { this.starred = starred; }

    public List<FileInfo> getInputFiles() { 
        return inputFiles != null ? inputFiles : new ArrayList<>(); 
    }
    public void setInputFiles(List<FileInfo> inputFiles) { this.inputFiles = inputFiles; }

    public List<FileInfo> getOutputFiles() { 
        return outputFiles != null ? outputFiles : new ArrayList<>(); 
    }
    public void setOutputFiles(List<FileInfo> outputFiles) { this.outputFiles = outputFiles; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
}
