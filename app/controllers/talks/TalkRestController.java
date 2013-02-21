package controllers.talks;

import static play.libs.Json.toJson;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import models.Comment;
import models.Creneau;
import models.StatusTalk;
import models.Tag;
import models.Talk;
import models.User;
import models.Vote;
import models.VoteStatus;
import models.VoteStatusEnum;
import models.utils.TransformValidationErrors;

import org.codehaus.jackson.JsonNode;

import play.Logger;
import play.data.Form;
import play.i18n.Messages;
import play.mvc.Controller;
import play.mvc.Result;
import securesocial.core.Identity;
import securesocial.core.java.SecureSocial;

@SecureSocial.SecuredAction(ajaxCall = true)
public class TalkRestController extends Controller {

    public static User getLoggedUser() {
        Identity socialUser = (Identity) ctx().args.get(SecureSocial.USER_KEY);
        User user = User.findByExternalId(socialUser.id().id(), socialUser.id().providerId());
        return user;
    }

    public static Result getById(Long idTalk) {
        Talk talk = Talk.find.byId(idTalk);

        User user = getLoggedUser();
        if (user.admin) {
            talk.vote = Vote.findVoteByUserAndTalk(user, talk);
        }
        talk.fiteredComments(user);
        return ok(toJson(talk));
    }

    public static Result get() {
        User user = getLoggedUser();

        List<Talk> talks = Talk.findBySpeaker(user);

        for (Talk talk : talks) {
            if (user.admin) {
                talk.vote = Vote.findVoteByUserAndTalk(user, talk);
                if (VoteStatus.getVoteStatus() == VoteStatusEnum.CLOSED) {
                    talk.moyenne = Vote.calculMoyenne(talk);
                }
            }

            talk.fiteredComments(user);
        }
        return ok(toJson(talks));
    }

    public static Result getTalks(Long userId) {
        User user = User.find.byId(userId);
        List<Talk> talks = Talk.findBySpeaker(user);
        for (Talk talk : talks) {
            talk.fiteredComments(user);
        }
        return ok(toJson(talks));
    }

    public static Result getTalksByStatus(Long userId, String status) {
        StatusTalk statusTalk = StatusTalk.fromCode(status);
        User user = User.find.byId(userId);
        List<Talk> talks = Talk.findBySpeakerAndStatus(user, statusTalk);
        for (Talk talk : talks) {
            talk.fiteredComments(user);
        }
        return ok(toJson(talks));
    }

    public static Result all() {
        User user = getLoggedUser();
        if (!user.admin) {
            return forbidden();
        }
        List<Talk> talks = Talk.find.all();
        for (Talk talk : talks) {
            talk.vote = Vote.findVoteByUserAndTalk(user, talk);
            if (VoteStatus.getVoteStatus() == VoteStatusEnum.CLOSED) {
                talk.moyenne = Vote.calculMoyenne(talk);
            }
            talk.fiteredComments(user);
        }
        return ok(toJson(talks));
    }

    public static Result save() {
        if (VoteStatus.getVoteStatus() != VoteStatusEnum.NOT_BEGIN) {
            return badRequest(toJson(TransformValidationErrors.transform(Messages.get("error.vote.begin"))));
        }
        User user = getLoggedUser();
        Form<Talk> talkForm = form(Talk.class).bindFromRequest();

        if (talkForm.hasErrors()) {
            // Has only speaker error?
            boolean onlySpeakerError = true;
            for (String key : talkForm.errors().keySet()) {
                if (!key.startsWith("speaker")) {
                    onlySpeakerError = false;
                }
            }
            if (!onlySpeakerError) {
                return badRequest(toJson(TransformValidationErrors.transform(talkForm.errors())));
            }
        }

        Talk formTalk = talkForm.get();

        if (formTalk.id == null) {
            // Nouveau talk
            formTalk.speaker = user;
            if (Talk.findByTitle(formTalk.title) != null) {
                return badRequest(toJson(TransformValidationErrors.transform(Messages.get("error.talk.already.exist"))));
            }
            if (formTalk.getCreneaux() == null || formTalk.getCreneaux().isEmpty()) {
                return badRequest(toJson(TransformValidationErrors.transform(Messages.get("error.talk.creneaux.empty"))));
            }

            formTalk.save();
            formTalk.saveManyToManyAssociations("creneaux");
            formTalk.update();
            updateTags(talkForm.data().get("tagsname"), formTalk);
        } else {
            // Mise à jour d'un talk
            Talk dbTalk = Talk.find.byId(formTalk.id);
            if (!formTalk.title.equals(dbTalk.title)
                    && Talk.findByTitle(formTalk.title) != null) {
                return badRequest(toJson(TransformValidationErrors.transform(Messages.get("error.talk.already.exist"))));
            }
            dbTalk.title = formTalk.title;
            dbTalk.description = formTalk.description;
            dbTalk.save();
            updateCreneaux(formTalk, dbTalk);
            updateTags(talkForm.data().get("tagsname"), dbTalk);
        }


        // HTTP 204 en cas de succès (NO CONTENT)
        return noContent();
    }

    private static void updateCreneaux(Talk formTalk, Talk dbTalk) {
        Set<Long> creneauxInForm = new HashSet<Long>();
        for (Creneau creneau : formTalk.getCreneaux()) {
            creneauxInForm.add(creneau.getId());
        }
        List<Creneau> creneauxTmp = new ArrayList<Creneau>(dbTalk.getCreneaux());
        Set<Long> creneauxInDb = new HashSet<Long>();
        for (Creneau creneau : creneauxTmp) {
            if (!creneauxInForm.contains(creneau.getId())) {
                dbTalk.getCreneaux().remove(creneau);
            } else {
                creneauxInDb.add(creneau.getId());
            }
        }

        // ajout des creneaux ajoutés dans la liste
        for (Long idCreneau : creneauxInForm) {
            if (!creneauxInDb.contains(idCreneau)) {
                dbTalk.getCreneaux().add(Creneau.find.byId(idCreneau));
            }
        }
        dbTalk.saveManyToManyAssociations("creneaux");

        dbTalk.dureePreferee = formTalk.dureePreferee;
        //formTalk.dureePreferee.talksPrefere.add(dbTalk);
        //formTalk.dureePreferee.save();

        dbTalk.update();
    }

    public static void updateTags(String tags, Talk dbTalk) {
        if (tags == null || tags.length() == 0) {
            return;
        }
        List<String> tagsList = Arrays.asList(tags.split(","));

        // suppression qui ne sont plus présent dans la nouvelle liste
        List<Tag> tagtmp = new ArrayList<Tag>(dbTalk.getTags());
        for (Tag tag : tagtmp) {
            if (!tagsList.contains(tag.nom)) {
                dbTalk.getTags().remove(tag);
            }
        }

        // ajout des tags ajoutés dans la liste
        for (String tag : tagsList) {
            if (!dbTalk.getTagsName().contains(tag)) {
                Tag dbTag = Tag.findByTagName(tag.toUpperCase());
                if (dbTag == null) {
                    dbTag = new Tag();
                    dbTag.nom = tag.toUpperCase();
                    dbTag.save();
                }
                Logger.debug("tags: = " + dbTag.id);
                dbTalk.getTags().add(dbTag);
            }
        }
        dbTalk.saveManyToManyAssociations("tags");
        dbTalk.update();
    }

    public static Result addTag(Long idTalk, String tags) {
        User user = getLoggedUser();
        Talk dbTalk = Talk.find.byId(idTalk);

        if (!user.admin && !user.id.equals(dbTalk.speaker.id)) {
            return forbidden(toJson(TransformValidationErrors.transform("Action non autorisée")));
        }

        if (dbTalk != null) {
            Logger.debug("addTags: = " + tags + " init tags " + dbTalk.getTagsName());
            updateTags(tags, dbTalk);
            Logger.debug("fin addTags: = " + dbTalk.getTagsName() + " size : " + dbTalk.getTags().size());
            return ok();
        } else {
            return notFound();
        }
    }

    public static Result delete(Long idTalk) {
        if (VoteStatus.getVoteStatus() != VoteStatusEnum.NOT_BEGIN) {
            return badRequest(toJson(TransformValidationErrors.transform(Messages.get("error.vote.begin"))));
        }

        Talk talk = Talk.find.byId(idTalk);
        for (Comment comment : talk.getComments()) {
            comment.delete();
        }

        List<Tag> tagtmp = new ArrayList<Tag>(talk.getTags());
        for (Tag tag : tagtmp) {
            talk.getTags().remove(tag);
        }
        talk.saveManyToManyAssociations("tags");

        List<Creneau> creneauxTmp = new ArrayList<Creneau>(talk.getCreneaux());
        for (Creneau creneau : creneauxTmp) {
            talk.getCreneaux().remove(creneau);
        }
        talk.saveManyToManyAssociations("creneaux");
        talk.delete();
        // HTTP 204 en cas de succès (NO CONTENT)
        return noContent();
    }

    public static Result saveComment(Long idTalk) {
        User user = getLoggedUser();
        Talk talk = Talk.find.byId(idTalk);

        JsonNode node = request().body().asJson();
        String commentForm = null;
        boolean privateComment = false;
        if (node != null && node.get("comment") != null) {
            commentForm = node.get("comment").asText();
            if (user.admin && node.get("private") != null) {
                privateComment = node.get("private").asBoolean();
            }
        } else {
            Map<String, List<String>> errors = new HashMap<String, List<String>>();
            errors.put("comment", Collections.singletonList(Messages.get("error.required")));
            return badRequest(toJson(errors));
        }

        if (!user.admin && !user.id.equals(talk.speaker.id)) {
            return forbidden(toJson(TransformValidationErrors.transform("Action non autorisée")));
        }

        if (commentForm.length() > 0 && commentForm.length() <= 140) {
            Comment comment = new Comment();
            comment.author = user;
            comment.comment = commentForm;
            comment.privateComment = privateComment;
            comment.talk = talk;
            comment.save();
            comment.sendMail();
        }
        return ok();
    }

    public static Result closeComment(Long idTalk, Long idComment) {
        User user = getLoggedUser();
        Talk talk = Talk.find.byId(idTalk);
        Comment question = Comment.find.byId(idComment);

        if (question.author.id != user.id) {
            Map<String, List<String>> errors = new HashMap<String, List<String>>();
            errors.put("error", Collections.singletonList(Messages.get("error.delete.comment.baduser")));
            return badRequest(toJson(errors));
        }

        question.clos = true;
        question.save();

        return ok();

    }

    public static Result deleteComment(Long idTalk, Long idComment) {
        User user = getLoggedUser();
        Talk talk = Talk.find.byId(idTalk);
        Comment question = Comment.find.byId(idComment);

        if (question.author.id != user.id && !user.admin) {
            Map<String, List<String>> errors = new HashMap<String, List<String>>();
            errors.put("error", Collections.singletonList(Messages.get("error.close.comment.baduser")));
            return badRequest(toJson(errors));
        }

        if (question.reponses != null && question.reponses.size() > 0) {
            Map<String, List<String>> errors = new HashMap<String, List<String>>();
            errors.put("error", Collections.singletonList(Messages.get("error.delete.comment")));
            return badRequest(toJson(errors));
        }

        question.delete();

        return ok();
    }

    public static Result saveReponse(Long idTalk, Long idComment) {
        User user = getLoggedUser();
        Talk talk = Talk.find.byId(idTalk);
        Comment question = Comment.find.byId(idComment);

        JsonNode node = request().body().asJson();
        String commentForm = null;
        boolean privateComment = false;
        Logger.debug("nose : " + node.asText());
        if (node != null && node.get("comment") != null) {
            commentForm = node.get("comment").asText();
            if (user.admin && node.get("private") != null) {
                privateComment = node.get("private").asBoolean();
            }
        } else {
            Map<String, List<String>> errors = new HashMap<String, List<String>>();
            errors.put("commentR", Collections.singletonList(Messages.get("error.required")));
            return badRequest(toJson(errors));
        }

        if (!user.admin && !user.id.equals(talk.speaker.id)) {
            return forbidden(toJson(TransformValidationErrors.transform("Action non autorisée")));
        }

        if (commentForm.length() > 0 && commentForm.length() <= 140) {
            Comment comment = new Comment();
            comment.author = user;
            comment.comment = commentForm;
            comment.privateComment = privateComment;
            comment.talk = talk;


            if (question.reponses == null) {
                question.reponses = new ArrayList<Comment>();
            }

            comment.question = question;
            comment.save();

            question.reponses.add(comment);
            question.save();

            comment.sendMail();
        }
        return ok();
    }

    public static Result editComment(Long idTalk, Long idComment) {
        User user = getLoggedUser();
        Talk talk = Talk.find.byId(idTalk);
        Comment question = Comment.find.byId(idComment);

        JsonNode node = request().body().asJson();
        String commentForm = null;
        Logger.debug("nose : " + node.asText());
        if (node != null && node.get("comment") != null) {
            commentForm = node.get("comment").asText();
        } else {
            Map<String, List<String>> errors = new HashMap<String, List<String>>();
            errors.put("commentE", Collections.singletonList(Messages.get("error.required")));
            return badRequest(toJson(errors));
        }

        if (!user.admin && !user.id.equals(talk.speaker.id)) {
            return forbidden(toJson(TransformValidationErrors.transform("Action non autorisée")));
        }

        if (commentForm.length() > 0 && commentForm.length() <= 140) {
            question.comment = commentForm;
            question.save();

            question.sendMail();
        }
        return ok();
    }

    public static Result saveStatus(Long idTalk) throws MalformedURLException {
        User user = getLoggedUser();
        if (!user.admin) {
            return forbidden();
        }

        Talk talk = Talk.find.byId(idTalk);

        JsonNode node = request().body().asJson();

        StatusTalk newStatus = StatusTalk.fromValue(node.get("status").asText());
        if (talk.statusTalk != newStatus) {
            talk.statusTalk = newStatus;
            talk.save();
            if (talk.statusTalk != null) {
                talk.statusTalk.sendMail(talk, talk.speaker.email);
            }
        }

        return ok();
    }

    public static Result saveVote(Long idTalk, Integer note) {
        User user = getLoggedUser();
        if (!user.admin) {
            return forbidden();
        }

        Talk talk = Talk.find.byId(idTalk);

        VoteStatusEnum voteStatus = VoteStatus.getVoteStatus();
        if (voteStatus != VoteStatusEnum.OPEN) {
            return unauthorized();
        }
        if (note == null || note < 1 || note > 5) {
            return badRequest();
        }

        Vote vote = Vote.findVoteByUserAndTalk(user, talk);
        if (vote == null) {
            vote = new Vote();
            vote.setUser(user);
            vote.setTalk(talk);
        }
        vote.setNote(note);
        vote.save();
        return ok();
    }
}
