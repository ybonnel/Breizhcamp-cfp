package models;

import com.google.common.base.Joiner;
import models.utils.BooleanUtils;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;
import play.data.format.Formats;
import play.data.validation.Constraints;
import play.db.ebean.Model;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("serial")
@Entity
public class Talk extends Model {
	
	@Id
	public Long id;
	
	@Constraints.Required
	@Constraints.MaxLength(50)	
	@Formats.NonEmpty
	@Column(unique = true, length = 50)
	public String title;
	
	@Constraints.Required
	@Constraints.MaxLength(2000)
	@Formats.NonEmpty
	@Column(length = 2000)
	public String description;
	
	@ManyToOne
	public User speaker;
	
	@OneToMany(mappedBy ="talk")
    @JsonIgnore
	public List<Comment> comments;
	
	public List<Comment> getComments() {
		if (comments == null) {
			comments = new ArrayList<Comment>();
		}
		return comments;
	}

    public StatusTalk statusTalk;

    @ManyToMany(mappedBy = "talks")
    @JsonIgnore
    public List<Tag> tags = new ArrayList<Tag>();

    public List<Tag> getTags() {
        if (tags == null) {
            tags = new ArrayList<Tag>();
        }
        return tags;
    }

    @JsonProperty(value = "tagsname")
    public String getTagsName() {
        return Joiner.on(",").join(tags);
    }

    @JsonIgnore
    public transient List<Comment> commentsFiltered;

    public void fiteredComments(User user) {
        if (user.admin) {
            commentsFiltered = new ArrayList<Comment>();
            for (Comment comment : comments) {
                if (comment.question == null) {
                    commentsFiltered.add(comment);
                }
            }
        } else {
            commentsFiltered = new ArrayList<Comment>();
            for (Comment comment : comments) {
                if (comment.question == null && BooleanUtils.isNotTrue(comment.privateComment)) {
                    commentsFiltered.add(comment);
                }
            }
        }
    }

    @JsonProperty("comments")
    public List<Comment> getCommentsFiltered() {
        return commentsFiltered;
    }

    @ManyToMany( mappedBy = "talks" )
    private List<Creneau> creneaux;

    @ManyToOne
    public Creneau dureePreferee;

    @ManyToOne
    public Creneau dureeApprouve;

    public List<Creneau> getCreneaux() {
        if (creneaux == null) {
            creneaux = new ArrayList<Creneau>();
        }
        return creneaux;
    }

    public static Finder<Long, Talk> find = new Finder<Long, Talk>(Long.class, Talk.class);
	
	
	public static Talk findByTitle(String title) {
		return find.where().eq("title", title).findUnique();
	}
	
	public static List<Talk> findBySpeaker(User speaker) {
		return find.where().eq("speaker", speaker).findList();
	}

    public static List<Talk> findBySpeakerAndStatus(User speaker,StatusTalk status) {
        return find.where().eq("statusTalk", status.getInterne()).eq("speaker", speaker).findList();
    }

    public static List<Talk> findByStatus(StatusTalk status) {
        return find.where().eq("statusTalk", status.getInterne()).findList();
    }

    @JsonIgnore
    public transient Vote vote;

    public transient Double moyenne;

    @JsonProperty("note")
    public Integer note() {
        return vote ==null ? 0 : vote.getNote();
    }
}
