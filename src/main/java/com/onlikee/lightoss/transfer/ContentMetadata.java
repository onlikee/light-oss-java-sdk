package com.onlikee.lightoss.transfer;

import com.onlikee.lightoss.model.Visibility;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Metadata exposed by an object, site-content, or archive response.
 *
 * @param contentLength content length, if declared
 * @param contentType media type, if declared
 * @param etag entity tag, if declared
 * @param visibility object visibility, if declared
 * @param originalFilename decoded original filename, if declared
 * @param contentDisposition content-disposition header, if declared
 */
public record ContentMetadata(
        OptionalLong contentLength,
        Optional<String> contentType,
        Optional<String> etag,
        Optional<Visibility> visibility,
        Optional<String> originalFilename,
        Optional<String> contentDisposition) {
    /** Creates immutable content metadata. */
    public ContentMetadata {
        contentLength = contentLength == null ? OptionalLong.empty() : contentLength;
        contentType = contentType == null ? Optional.empty() : contentType;
        etag = etag == null ? Optional.empty() : etag;
        visibility = visibility == null ? Optional.empty() : visibility;
        originalFilename = originalFilename == null ? Optional.empty() : originalFilename;
        contentDisposition = contentDisposition == null ? Optional.empty() : contentDisposition;
    }
}
