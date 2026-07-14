package com.jinloes.prpilot.ui;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * Compile-time-verified mapping from core model types to webview DTO records. MapStruct generates
 * the implementation at build time based on matching getter/constructor-component names, so adding
 * a field to {@link ReviewResult} or {@link LineComment} produces a compile error here rather than
 * a silent runtime omission.
 */
@Mapper(unmappedSourcePolicy = ReportingPolicy.ERROR, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ReviewMapper {

    ReviewMapper INSTANCE = Mappers.getMapper(ReviewMapper.class);

    @BeanMapping(
            ignoreUnmappedSourceProperties = {
                "_summary$core",
                "_verdict$core",
                "_lineComments$core"
            })
    ReviewResultDto toDto(ReviewResult result);

    @BeanMapping(
            ignoreUnmappedSourceProperties = {
                "_file$core",
                "_line$core",
                "_type$core",
                "_body$core",
                "_severity$core",
                "_category$core",
                "_confidence$core",
                "_rationale$core"
            })
    LineCommentDto toDto(LineComment comment);
}
