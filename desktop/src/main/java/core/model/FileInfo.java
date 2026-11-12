package core.model;

import java.io.Serializable;

public class FileInfo implements Serializable {
    private String fileName;
    private String originalFileName;
    private String filePath;
    private Long fileSize;
    private Long originalFileSize;
    private String status;
    private Boolean signatureValid = false;
    private Boolean isSplit = false;
    private Boolean isFolder = false;
    private Integer volumeCount;
    private Integer currentVolume;
    private String sha256Hash;
    private String errorMessage;
    private String description;

    public FileInfo() {}

    public FileInfo(String fileName, String filePath, long fileSize, String status) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.status = status;
    }

    public FileInfo(String fileName, String filePath, long fileSize, String status, String sha256Hash, Boolean signatureValid) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.status = status;
        this.sha256Hash = sha256Hash;
        this.signatureValid = signatureValid != null ? signatureValid : false;
    }

    public FileInfo(String fileName, String filePath, long fileSize, String status, String errorMessage) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public FileInfo(String fileName, String originalFileName, String filePath, Long fileSize, Long originalFileSize, 
                   String status, Boolean signatureValid, Boolean isSplit, Boolean isFolder, 
                   Integer volumeCount, Integer currentVolume) {
        this.fileName = fileName;
        this.originalFileName = originalFileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.originalFileSize = originalFileSize;
        this.status = status;
        this.signatureValid = signatureValid != null ? signatureValid : false;
        this.isSplit = isSplit != null ? isSplit : false;
        this.isFolder = isFolder != null ? isFolder : false;
        this.volumeCount = volumeCount;
        this.currentVolume = currentVolume;
    }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public Long getOriginalFileSize() { return originalFileSize; }
    public void setOriginalFileSize(Long originalFileSize) { this.originalFileSize = originalFileSize; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Boolean getSignatureValid() { return signatureValid != null ? signatureValid : false; }
    public void setSignatureValid(Boolean signatureValid) { this.signatureValid = signatureValid != null ? signatureValid : false; }

    public Boolean getIsSplit() { return isSplit != null ? isSplit : false; }
    public void setIsSplit(Boolean isSplit) { this.isSplit = isSplit != null ? isSplit : false; }

    public Boolean getIsFolder() { return isFolder != null ? isFolder : false; }
    public void setIsFolder(Boolean isFolder) { this.isFolder = isFolder != null ? isFolder : false; }

    public Integer getVolumeCount() { return volumeCount; }
    public void setVolumeCount(Integer volumeCount) { this.volumeCount = volumeCount; }

    public Integer getCurrentVolume() { return currentVolume; }
    public void setCurrentVolume(Integer currentVolume) { this.currentVolume = currentVolume; }

    public String getSha256Hash() { return sha256Hash; }
    public void setSha256Hash(String sha256Hash) { this.sha256Hash = sha256Hash; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
